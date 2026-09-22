package io.github.stevdrey.dokene.recommendation.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Bounded confidence score for an AI recommendation, strictly between 0.0 and 1.0 inclusive.
 */
public record RecommendationConfidence(@JsonValue double value) {
    public RecommendationConfidence {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new RecommendationValidationException("confidence", "Confidence must be a finite number");
        }
        if (value < 0.0 || value > 1.0) {
            throw new RecommendationValidationException("confidence", "Confidence must be between 0.0 and 1.0, got: " + value);
        }
    }

    @JsonCreator
    public static RecommendationConfidence of(double value) {
        return new RecommendationConfidence(value);
    }
}
