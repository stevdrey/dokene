package io.github.stevdrey.dokene.ai.telemetry;

import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;

/**
 * Micrometer implementation. Tags come exclusively from closed vocabularies; tenant, customer, actor, correlation
 * identifiers and free text are deliberately never used as dimensions (cardinality and cross-tenant channel).
 */
public final class MicrometerAiTelemetry implements AiTelemetry {
    static final String ATTEMPTS = "dokene.ai.attempts";
    static final String OUTCOMES = "dokene.ai.outcomes";
    static final String DURATION = "dokene.ai.attempt.duration";
    static final String TOKENS = "dokene.ai.tokens";
    static final String RETRIES = "dokene.ai.retries";
    static final String REFUSALS = "dokene.ai.model.refusals";
    static final String GATE_REJECTIONS = "dokene.ai.gate.rejections";
    private static final String NONE = "none";

    private final MeterRegistry registry;

    public MicrometerAiTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "Meter registry is required");
    }

    @Override
    public void attemptCompleted(AiOperation operation, AiInvocationMetadata metadata,
            AiFailureCategory category) {
        Tags tags = Tags.of(
                "operation", operation.name(),
                "provider", metadata.providerId(),
                "model", metadata.modelId() == null ? NONE : metadata.modelId(),
                "outcome", metadata.status().name(),
                "category", category == null ? NONE : category.name());
        registry.counter(ATTEMPTS, tags).increment();
        Timer.builder(DURATION)
                .tags(tags.and("operation", operation.name()))
                .register(registry)
                .record(metadata.latency());
        if (metadata.usage() != null) {
            Tags usageTags = Tags.of("operation", operation.name(), "provider", metadata.providerId(),
                    "model", metadata.modelId() == null ? NONE : metadata.modelId());
            registry.counter(TOKENS, usageTags.and("direction", "input")).increment(metadata.usage().inputTokens());
            registry.counter(TOKENS, usageTags.and("direction", "output")).increment(metadata.usage().outputTokens());
        }
    }

    @Override
    public void outcome(AiOperation operation, Outcome outcome) {
        registry.counter(OUTCOMES, "operation", operation.name(), "outcome", outcome.name()).increment();
    }

    @Override
    public void retryScheduled(AiOperation operation, String providerId, AiFailureCategory category) {
        registry.counter(RETRIES, "operation", operation.name(), "provider", providerId,
                "category", category.name()).increment();
    }

    @Override
    public void modelRefusal(AiOperation operation) {
        registry.counter(REFUSALS, "operation", operation.name()).increment();
    }

    @Override
    public void gateRejected(AiOperation operation, String reason) {
        // The tag value must come from the closed vocabulary; anything else cannot become a metric dimension.
        String tag = reason != null && GATE_REJECTION_REASONS.contains(reason) ? reason : UNKNOWN_REASON;
        registry.counter(GATE_REJECTIONS, "operation", operation.name(), "reason", tag).increment();
    }
}
