package io.github.stevdrey.dokene.ai.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Advisory recommendation advocating for a specific semantic follow-up action and template intent.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ActionRecommendation(
        @JsonProperty("action") SemanticAction action,
        @JsonProperty("templateIntent") SemanticTemplateIntent templateIntent,
        @JsonProperty("rationale") String rationale,
        @JsonProperty("confidence") RecommendationConfidence confidence,
        @JsonProperty("draftVariables") DraftVariables draftVariables) implements RecommendationOutcome {

    public ActionRecommendation {
        if (action == null) {
            throw new RecommendationValidationException("action", "Action is required");
        }
        if (templateIntent == null) {
            throw new RecommendationValidationException("templateIntent", "Template intent is required");
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
        if (draftVariables == null) {
            throw new RecommendationValidationException("draftVariables", "Draft variables is required");
        }
    }
}
