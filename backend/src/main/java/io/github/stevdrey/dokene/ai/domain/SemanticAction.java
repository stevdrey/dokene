package io.github.stevdrey.dokene.ai.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Closed, allowlisted semantic actions that AI may recommend.
 * Model output must map strictly to one of these values; unknown values fail validation.
 */
public enum SemanticAction {
    REPEAT_PURCHASE_FOLLOW_UP,
    GENERAL_CHECK_IN,
    RELATED_PRODUCT_OFFER,
    DORMANT_REENGAGEMENT,
    SEASONAL_GREETING;

    @JsonCreator
    public static SemanticAction from(String value) {
        if (value == null || value.isBlank()) {
            throw new RecommendationValidationException("action", "Semantic action is required");
        }
        try {
            return SemanticAction.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new RecommendationValidationException("action", "Unknown semantic action: " + value);
        }
    }
}
