package io.github.stevdrey.dokene.ai.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiTokenUsage;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MicrometerAiTelemetryTest {
    private static final Set<String> ALLOWED_TAGS = Set.of(
            "operation", "provider", "model", "outcome", "category", "direction", "reason");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerAiTelemetry telemetry = new MicrometerAiTelemetry(registry);

    @Test
    void recordsSuccessWithLatencyAndTokenUsage() {
        telemetry.invocationCompleted(AiOperation.NEXT_BEST_ACTION, new AiInvocationMetadata("openai", "gpt-6-luna",
                "req_1", Duration.ofMillis(250), new AiTokenUsage(120, 30), AiCompletionStatus.SUCCEEDED), null);

        assertThat(registry.get(MicrometerAiTelemetry.INVOCATIONS)
                .tag("operation", "NEXT_BEST_ACTION").tag("provider", "openai").tag("model", "gpt-6-luna")
                .tag("outcome", "SUCCEEDED").tag("category", "none").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MicrometerAiTelemetry.DURATION).timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(250.0);
        assertThat(registry.get(MicrometerAiTelemetry.TOKENS).tag("direction", "input").counter().count())
                .isEqualTo(120.0);
        assertThat(registry.get(MicrometerAiTelemetry.TOKENS).tag("direction", "output").counter().count())
                .isEqualTo(30.0);
    }

    @Test
    void recordsFailureCategoryAndOmitsTokensWhenProviderSuppliesNone() {
        telemetry.invocationCompleted(AiOperation.MESSAGE_DRAFT, new AiInvocationMetadata("openai", null, null,
                Duration.ofSeconds(15), null, AiCompletionStatus.FAILED), AiFailureCategory.TIMEOUT);

        assertThat(registry.get(MicrometerAiTelemetry.INVOCATIONS)
                .tag("category", "TIMEOUT").tag("outcome", "FAILED").tag("model", "none").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find(MicrometerAiTelemetry.TOKENS).counters()).isEmpty();
    }

    @Test
    void recordsRetriesRefusalsAndGateRejections() {
        telemetry.retryScheduled(AiOperation.NEXT_BEST_ACTION, "openai", AiFailureCategory.THROTTLED);
        telemetry.modelRefusal(AiOperation.MESSAGE_DRAFT);
        telemetry.gateRejected(AiOperation.NEXT_BEST_ACTION, "DISALLOWED_ACTION");

        assertThat(registry.get(MicrometerAiTelemetry.RETRIES).tag("category", "THROTTLED").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(MicrometerAiTelemetry.REFUSALS).tag("operation", "MESSAGE_DRAFT").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(MicrometerAiTelemetry.GATE_REJECTIONS).tag("reason", "DISALLOWED_ACTION")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void neverUsesTenantCustomerActorOrCorrelationDimensions() {
        telemetry.invocationCompleted(AiOperation.NEXT_BEST_ACTION, new AiInvocationMetadata("openai", "m", "r",
                Duration.ofMillis(1), new AiTokenUsage(1, 1), AiCompletionStatus.SUCCEEDED), null);
        telemetry.retryScheduled(AiOperation.NEXT_BEST_ACTION, "openai", AiFailureCategory.UNAVAILABLE);
        telemetry.modelRefusal(AiOperation.NEXT_BEST_ACTION);
        telemetry.gateRejected(AiOperation.NEXT_BEST_ACTION, "STALE_STATE");

        Set<String> tagKeys = registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getKey)
                .collect(Collectors.toSet());
        assertThat(tagKeys).isSubsetOf(ALLOWED_TAGS);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getName())
                .startsWith("dokene.ai."));
    }
}
