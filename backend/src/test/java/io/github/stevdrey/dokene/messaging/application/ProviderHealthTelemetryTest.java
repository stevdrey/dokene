// ABOUTME: Pins the provider health meters (ADR 0038): names, the closed tag sets, and the state gauge's memo and
// ABOUTME: its behavior when the health row cannot be read (last value for one more period, then no value).
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.messaging.application.ProviderHealthReader.ProviderHealthStatus;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthTelemetry.GateDecision;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthTelemetry.StoreOperation;
import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy.RefusalReason;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthSettings.Mode;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthState;
import io.github.stevdrey.dokene.messaging.domain.ProviderProbeResult;
import io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ProviderHealthTelemetryTest {
    private static final MessagingProviderKey META = MessagingProviderKey.META_WHATSAPP;
    private static final long MEMO = ProviderHealthStateGauge.MEMO.toNanos();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong nanos = new AtomicLong(1_000);
    private final AtomicInteger reads = new AtomicInteger();
    private volatile Supplier<ProviderHealthState> stored = () -> ProviderHealthState.HEALTHY;

    @Test
    void countersCarryOnlyTheProviderAndClosedValues() {
        ProviderHealthTelemetry telemetry = new MicrometerProviderHealthTelemetry(registry);

        telemetry.signal(META, ProviderSignalKind.OUTAGE);
        telemetry.transition(META, ProviderHealthState.DEGRADED, ProviderHealthState.UNAVAILABLE);
        telemetry.storeFailure(META, StoreOperation.RECORD);

        assertThat(registry.get("dokene.messaging.provider.health.signals")
                .tags("provider", "META_WHATSAPP", "kind", "OUTAGE").counter().count()).isEqualTo(1);
        assertThat(registry.get("dokene.messaging.provider.health.transitions")
                .tags("provider", "META_WHATSAPP", "from", "DEGRADED", "to", "UNAVAILABLE").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("dokene.messaging.provider.health.store.failures")
                .tags("provider", "META_WHATSAPP", "operation", "RECORD").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).extracting(Meter::getId)
                .allSatisfy(id -> assertThat(id.getTags()).extracting(Tag::getKey)
                        .isSubsetOf("provider", "kind", "from", "to", "operation"));
    }

    @Test
    void gateCountersCarryOnlyTheProviderTheModeAndClosedValues() {
        ProviderHealthTelemetry telemetry = new MicrometerProviderHealthTelemetry(registry);

        telemetry.decision(META, Mode.OBSERVE, GateDecision.REFUSE);
        telemetry.refusal(META, RefusalReason.PROBE_IN_FLIGHT);
        telemetry.probe(META, ProviderProbeResult.PASSED_THROTTLED);
        telemetry.storeFailure(META, StoreOperation.LEASE);
        telemetry.storeFailure(META, StoreOperation.RESULT);

        assertThat(registry.get("dokene.messaging.provider.health.decisions")
                .tags("provider", "META_WHATSAPP", "mode", "observe", "decision", "REFUSE").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("dokene.messaging.provider.health.refusals")
                .tags("provider", "META_WHATSAPP", "reason", "PROBE_IN_FLIGHT").counter().count()).isEqualTo(1);
        assertThat(registry.get("dokene.messaging.provider.health.probes")
                .tags("provider", "META_WHATSAPP", "result", "PASSED_THROTTLED").counter().count()).isEqualTo(1);
        assertThat(registry.get("dokene.messaging.provider.health.store.failures").counters()).hasSize(2);
        assertThat(registry.getMeters()).extracting(Meter::getId)
                .allSatisfy(id -> assertThat(id.getTags()).extracting(Tag::getKey)
                        .isSubsetOf("provider", "mode", "decision", "reason", "result", "operation"));
    }

    @Test
    void theGaugeIsOneForTheCurrentStateAndReadsTheRowOncePerMemoPeriod() {
        gauge();

        assertThat(states()).containsExactly(1.0, 0.0, 0.0, 0.0);
        stored = () -> ProviderHealthState.UNAVAILABLE;
        nanos.addAndGet(MEMO - 1);
        assertThat(states()).containsExactly(1.0, 0.0, 0.0, 0.0);
        assertThat(reads).hasValue(1);

        nanos.addAndGet(1);
        assertThat(states()).containsExactly(0.0, 0.0, 1.0, 0.0);
        assertThat(reads).hasValue(2);
    }

    @Test
    void anUnreadableRowKeepsTheLastValueForOneMorePeriodThenReportsNoValueUntilAReadWorks() {
        gauge();
        assertThat(states()).containsExactly(1.0, 0.0, 0.0, 0.0);

        stored = () -> {
            throw new IllegalStateException("unreadable");
        };
        nanos.addAndGet(MEMO);
        assertThat(states()).containsExactly(1.0, 0.0, 0.0, 0.0);
        nanos.addAndGet(MEMO);
        assertThat(states()).allSatisfy(value -> assertThat(value).isNaN());

        stored = () -> ProviderHealthState.DEGRADED;
        nanos.addAndGet(MEMO);
        assertThat(states()).containsExactly(0.0, 1.0, 0.0, 0.0);
    }

    private void gauge() {
        ProviderHealthReader reader = provider -> {
            reads.incrementAndGet();
            return new ProviderHealthStatus(stored.get(), Instant.EPOCH, Optional.empty());
        };
        new ProviderHealthStateGauge(reader, META, nanos::get).bindTo(registry);
    }

    /** The gauge values in the order HEALTHY, DEGRADED, UNAVAILABLE, UNKNOWN. */
    private List<Double> states() {
        return List.of(ProviderHealthState.values()).stream()
                .map(state -> registry.get(ProviderHealthStateGauge.STATE)
                        .tags("provider", "META_WHATSAPP", "state", state.name()).gauge().value())
                .toList();
    }
}
