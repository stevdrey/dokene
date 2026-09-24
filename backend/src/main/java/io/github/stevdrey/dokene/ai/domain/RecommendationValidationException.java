package io.github.stevdrey.dokene.ai.domain;

import java.util.Objects;

/**
 * Thrown when recommendation contracts or model outputs violate domain validation bounds or invariants.
 */
public class RecommendationValidationException extends IllegalArgumentException {
    private final String field;

    public RecommendationValidationException(String field, String message) {
        super(message);
        this.field = Objects.requireNonNull(field, "Field is required");
    }

    public String field() {
        return field;
    }
}
