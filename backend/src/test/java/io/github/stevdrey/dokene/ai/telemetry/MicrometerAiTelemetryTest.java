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
        telemetry.attemptCompleted(AiOperation.NEXT_BEST_ACTION, new AiInvocationMetadata("openai", "gpt-6-luna",
                "req_1", Duration.ofMillis(250), new AiTokenUsage(120, 30), AiCompletionStatus.SUCCEEDED), null);

        assertThat(registry.get(MicrometerAiTelemetry.ATTEMPTS)
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
        telemetry.attemptCompleted(AiOperation.MESSAGE_DRAFT, new AiInvocationMetadata("openai", null, null,
                Duration.ofSeconds(15), null, AiCompletionStatus.FAILED), AiFailureCategory.TIMEOUT);

        assertThat(registry.get(MicrometerAiTelemetry.ATTEMPTS)
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
    void gateRejectionReasonOutsideTheClosedVocabularyIsNeverUsedAsATagValue() {
        telemetry.gateRejected(AiOperation.NEXT_BEST_ACTION, "tenant-3f2a free text +593991234567");
        telemetry.gateRejected(AiOperation.NEXT_BEST_ACTION, null);
        telemetry.gateRejected(AiOperation.NEXT_BEST_ACTION, "STALE_STATE");

        assertThat(registry.get(MicrometerAiTelemetry.GATE_REJECTIONS).tag("reason", "UNKNOWN").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get(MicrometerAiTelemetry.GATE_REJECTIONS).tag("reason", "STALE_STATE").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find(MicrometerAiTelemetry.GATE_REJECTIONS).counters())
                .extracting(counter -> counter.getId().getTag("reason"))
                .containsExactlyInAnyOrder("UNKNOWN", "STALE_STATE");
    }

    @Test
    void logicalOutcomesAreCountedSeparatelyFromProviderAttempts() {
        telemetry.attemptCompleted(AiOperation.NEXT_BEST_ACTION, new AiInvocationMetadata("openai", "m", null,
                Duration.ofMillis(5), null, AiCompletionStatus.FAILED), AiFailureCategory.THROTTLED);
        telemetry.attemptCompleted(AiOperation.NEXT_BEST_ACTION, new AiInvocationMetadata("openai", "m", "r",
                Duration.ofMillis(5), null, AiCompletionStatus.SUCCEEDED), null);
        telemetry.outcome(AiOperation.NEXT_BEST_ACTION, new AiInvocationMetadata("openai", "m", "r",
                Duration.ofMillis(5), null, AiCompletionStatus.SUCCEEDED),
                io.github.stevdrey.dokene.ai.application.AiTelemetry.Outcome.GENERATED);

        // one request that needed a retry: two provider attempts, exactly one logical outcome
        assertThat(registry.find(MicrometerAiTelemetry.ATTEMPTS).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum()).isEqualTo(2.0);
        assertThat(registry.get(MicrometerAiTelemetry.OUTCOMES).tag("operation", "NEXT_BEST_ACTION")
                .tag("provider", "openai").tag("model", "m").tag("outcome", "GENERATED").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find(MicrometerAiTelemetry.OUTCOMES).counters()).hasSize(1);
    }

    @Test
    void outcomeTagsComeFromValidatedMetadataAndDefaultToNoneWhenNoProviderWasReached() {
        io.github.stevdrey.dokene.ai.application.AiTelemetry.Outcome failed =
                io.github.stevdrey.dokene.ai.application.AiTelemetry.Outcome.FAILED;
        telemetry.outcome(AiOperation.MESSAGE_DRAFT, new AiInvocationMetadata("disabled", "none", "none",
                Duration.ZERO, new AiTokenUsage(0, 0), AiCompletionStatus.FAILED), failed);
        telemetry.outcome(AiOperation.MESSAGE_DRAFT, new AiInvocationMetadata("openai", null, null,
                Duration.ofMillis(3), null, AiCompletionStatus.FAILED), failed);
        telemetry.outcome(AiOperation.MESSAGE_DRAFT, null, failed);

        assertThat(registry.get(MicrometerAiTelemetry.OUTCOMES).tag("provider", "disabled").tag("model", "none")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MicrometerAiTelemetry.OUTCOMES).tag("provider", "openai").tag("model", "none")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MicrometerAiTelemetry.OUTCOMES).tag("provider", "none").tag("model", "none")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find(MicrometerAiTelemetry.OUTCOMES).counters())
                .extracting(counter -> counter.getId().getTags().stream()
                        .map(io.micrometer.core.instrument.Tag::getKey).collect(Collectors.toSet()))
                .allSatisfy(keys -> assertThat(keys).containsExactlyInAnyOrder("operation", "provider", "model", "outcome"));
    }

    @Test
    void neverUsesTenantCustomerActorOrCorrelationDimensions() {
        telemetry.attemptCompleted(AiOperation.NEXT_BEST_ACTION, new AiInvocationMetadata("openai", "m", "r",
                Duration.ofMillis(1), new AiTokenUsage(1, 1), AiCompletionStatus.SUCCEEDED), null);
        telemetry.retryScheduled(AiOperation.NEXT_BEST_ACTION, "openai", AiFailureCategory.UNAVAILABLE);
        telemetry.modelRefusal(AiOperation.NEXT_BEST_ACTION);
        telemetry.gateRejected(AiOperation.NEXT_BEST_ACTION, "STALE_STATE");
        telemetry.outcome(AiOperation.NEXT_BEST_ACTION, new AiInvocationMetadata("openai", "m", null,
                Duration.ofMillis(1), null, AiCompletionStatus.FAILED),
                io.github.stevdrey.dokene.ai.application.AiTelemetry.Outcome.FAILED);

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
