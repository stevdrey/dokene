package io.github.stevdrey.dokene.ai.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Advisory outcome indicating that no follow-up message draft could or should be generated.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record NoDraft(
        @JsonProperty("reason") NoDraftReason reason,
        @JsonProperty("rationale") String rationale,
        @JsonProperty("confidence") RecommendationConfidence confidence) implements DraftOutcome {

    public NoDraft {
        if (reason == null) {
            throw new RecommendationValidationException("reason", "No-draft reason is required");
        }
        if (!RecommendationRationale.isNonBlank(rationale)) {
            throw new RecommendationValidationException("rationale", "Rationale is required");
        }
        if (rationale.codePointCount(0, rationale.length()) > MAX_RATIONALE_LENGTH) {
            throw new RecommendationValidationException("rationale",
                    "Rationale exceeds maximum length of " + MAX_RATIONALE_LENGTH + " characters");
        }
        rationale = RecommendationRationale.trim(rationale);
        if (confidence == null) {
            throw new RecommendationValidationException("confidence", "Confidence is required");
        }
    }
}
