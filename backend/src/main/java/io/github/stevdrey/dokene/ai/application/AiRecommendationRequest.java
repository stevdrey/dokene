package io.github.stevdrey.dokene.ai.application;

import java.time.Duration;
import java.util.Objects;

public record AiRecommendationRequest(AiOperation operation, RecommendationContext context, Duration timeout) {
    public AiRecommendationRequest {
        Objects.requireNonNull(operation, "Operation is required");
        if (operation != AiOperation.NEXT_BEST_ACTION) {
            throw new IllegalArgumentException("Recommendation requests require the NEXT_BEST_ACTION operation");
        }
        Objects.requireNonNull(context, "Recommendation context is required");
        Objects.requireNonNull(timeout, "Timeout is required");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
    }
}
