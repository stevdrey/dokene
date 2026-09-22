package io.github.stevdrey.dokene.recommendation.domain;

/**
 * Closed reasons explaining why no follow-up action was recommended by the model.
 * Models an explicit refusal / no-action outcome without placeholder actions.
 */
public enum NoRecommendationReason {
    INSUFFICIENT_HISTORY,
    RECENTLY_CONTACTED,
    NO_RELEVANT_OFFER,
    UNCERTAIN_INTENT,
    MANUAL_REVIEW_REQUIRED;

    public static NoRecommendationReason from(String value) {
        if (value == null || value.isBlank()) {
            throw new RecommendationValidationException("reason", "No-recommendation reason is required");
        }
        try {
            return NoRecommendationReason.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new RecommendationValidationException("reason", "Unknown no-recommendation reason: " + value);
        }
    }
}
