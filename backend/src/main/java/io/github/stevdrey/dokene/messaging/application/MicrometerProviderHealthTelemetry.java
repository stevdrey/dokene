// ABOUTME: Micrometer ProviderHealthTelemetry (ADR 0038): dokene.messaging.provider.health.signals, .transitions,
// ABOUTME: .store.failures, .decisions, .refusals and .probes, tagged only with the provider key and closed enum values.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy.RefusalReason;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthSettings.Mode;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthState;
import io.github.stevdrey.dokene.messaging.domain.ProviderProbeResult;
import io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

public final class MicrometerProviderHealthTelemetry implements ProviderHealthTelemetry {
    static final String SIGNALS = "dokene.messaging.provider.health.signals";
    static final String TRANSITIONS = "dokene.messaging.provider.health.transitions";
    static final String STORE_FAILURES = "dokene.messaging.provider.health.store.failures";
    static final String DECISIONS = "dokene.messaging.provider.health.decisions";
    static final String REFUSALS = "dokene.messaging.provider.health.refusals";
    static final String PROBES = "dokene.messaging.provider.health.probes";

    private final MeterRegistry registry;

    public MicrometerProviderHealthTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "Meter registry is required");
    }

    @Override
    public void signal(MessagingProviderKey provider, ProviderSignalKind kind) {
        registry.counter(SIGNALS, "provider", provider.name(), "kind", kind.name()).increment();
    }

    @Override
    public void transition(MessagingProviderKey provider, ProviderHealthState from, ProviderHealthState to) {
        registry.counter(TRANSITIONS, "provider", provider.name(), "from", from.name(), "to", to.name()).increment();
    }

    @Override
    public void storeFailure(MessagingProviderKey provider, StoreOperation operation) {
        registry.counter(STORE_FAILURES, "provider", provider.name(), "operation", operation.name()).increment();
    }

    @Override
    public void decision(MessagingProviderKey provider, Mode mode, GateDecision decision) {
        registry.counter(DECISIONS, "provider", provider.name(), "mode", mode.name().toLowerCase(Locale.ROOT),
                "decision", decision.name()).increment();
    }

    @Override
    public void refusal(MessagingProviderKey provider, RefusalReason reason) {
        registry.counter(REFUSALS, "provider", provider.name(), "reason", reason.name()).increment();
    }

    @Override
    public void probe(MessagingProviderKey provider, ProviderProbeResult result) {
        registry.counter(PROBES, "provider", provider.name(), "result", result.name()).increment();
    }
}
