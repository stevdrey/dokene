package io.github.stevdrey.dokene.recommendation.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Advisory outcome indicating that no follow-up action is recommended by the model.
 * Explicitly models refusal or lack of follow-up opportunity without placeholder actions.
 */
public record NoRecommendation(
        @JsonProperty("reason") NoRecommendationReason reason,
        @JsonProperty("rationale") String rationale,
        @JsonProperty("confidence") RecommendationConfidence confidence) implements RecommendationOutcome {

    public NoRecommendation {
        if (reason == null) {
            throw new RecommendationValidationException("reason", "No-recommendation reason is required");
        }
        if (rationale == null || rationale.isBlank()) {
            throw new RecommendationValidationException("rationale", "Rationale is required");
        }
        rationale = rationale.trim();
        if (rationale.length() > MAX_RATIONALE_LENGTH) {
            throw new RecommendationValidationException("rationale",
                    "Rationale exceeds maximum length of " + MAX_RATIONALE_LENGTH + " characters");
        }
        if (confidence == null) {
            throw new RecommendationValidationException("confidence", "Confidence is required");
        }
    }
}
