package io.github.stevdrey.dokene.ai.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Closed, allowlisted semantic message template intents managed by the application.
 * AI recommendation can select among these intents, but provider template IDs are mapped
 * deterministically by the application rather than invented by the model.
 */
public enum SemanticTemplateIntent {
    GENERAL_FOLLOW_UP,
    REPEAT_PURCHASE,
    RELATED_PRODUCT,
    SEASONAL_EVENT,
    DORMANT_CUSTOMER;

    @JsonCreator
    public static SemanticTemplateIntent from(String value) {
        if (value == null || value.isBlank()) {
            throw new RecommendationValidationException("templateIntent", "Semantic template intent is required");
        }
        try {
            return SemanticTemplateIntent.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new RecommendationValidationException("templateIntent", "Unknown semantic template intent: " + value);
        }
    }
}
