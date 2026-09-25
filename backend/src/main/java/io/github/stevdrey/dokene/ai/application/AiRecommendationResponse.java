package io.github.stevdrey.dokene.ai.application;

import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import java.util.Objects;

public record AiRecommendationResponse(RecommendationOutcome outcome, AiInvocationMetadata metadata) {
    public AiRecommendationResponse {
        Objects.requireNonNull(outcome, "Recommendation outcome is required");
        Objects.requireNonNull(metadata, "Invocation metadata is required");
        if (metadata.status() != AiCompletionStatus.SUCCEEDED) {
            throw new IllegalArgumentException("A recommendation response must have SUCCEEDED status");
        }
    }
}
