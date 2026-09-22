package io.github.stevdrey.dokene.recommendation.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationOutcomeSerializationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesAndDeserializesActionRecommendation() throws Exception {
        ActionRecommendation recommendation = new ActionRecommendation(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Customer purchased coffee beans 60 days ago",
                RecommendationConfidence.of(0.88),
                DraftVariables.of(Map.of("product", "Dark Roast", "days", "60"))
        );

        String json = objectMapper.writeValueAsString(recommendation);
        assertThat(json).contains("\"outcome\":\"ACTION\"");
        assertThat(json).contains("\"action\":\"REPEAT_PURCHASE_FOLLOW_UP\"");
        assertThat(json).contains("\"templateIntent\":\"REPEAT_PURCHASE\"");

        RecommendationOutcome deserialized = objectMapper.readValue(json, RecommendationOutcome.class);
        assertThat(deserialized).isInstanceOf(ActionRecommendation.class);
        ActionRecommendation actionRec = (ActionRecommendation) deserialized;
        assertThat(actionRec.action()).isEqualTo(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP);
        assertThat(actionRec.templateIntent()).isEqualTo(SemanticTemplateIntent.REPEAT_PURCHASE);
        assertThat(actionRec.rationale()).isEqualTo("Customer purchased coffee beans 60 days ago");
        assertThat(actionRec.confidence().value()).isEqualTo(0.88);
        assertThat(actionRec.draftVariables().asMap()).containsEntry("product", "Dark Roast");
    }

    @Test
    void serializesAndDeserializesNoRecommendation() throws Exception {
        NoRecommendation noRecommendation = new NoRecommendation(
                NoRecommendationReason.INSUFFICIENT_HISTORY,
                "Customer has only one purchase and no clear re-order cadence",
                RecommendationConfidence.of(0.95)
        );

        String json = objectMapper.writeValueAsString(noRecommendation);
        assertThat(json).contains("\"outcome\":\"NO_RECOMMENDATION\"");
        assertThat(json).contains("\"reason\":\"INSUFFICIENT_HISTORY\"");

        RecommendationOutcome deserialized = objectMapper.readValue(json, RecommendationOutcome.class);
        assertThat(deserialized).isInstanceOf(NoRecommendation.class);
        NoRecommendation noRec = (NoRecommendation) deserialized;
        assertThat(noRec.reason()).isEqualTo(NoRecommendationReason.INSUFFICIENT_HISTORY);
        assertThat(noRec.rationale()).isEqualTo("Customer has only one purchase and no clear re-order cadence");
        assertThat(noRec.confidence().value()).isEqualTo(0.95);
    }

    @Test
    void supportsJava26ExhaustivePatternMatching() {
        RecommendationOutcome action = new ActionRecommendation(
                SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP,
                "Regular touchpoint",
                RecommendationConfidence.of(0.75),
                DraftVariables.empty()
        );

        String outcomeDesc = switch (action) {
            case ActionRecommendation a -> "ACTION:" + a.action().name();
            case NoRecommendation nr -> "NO_ACTION:" + nr.reason().name();
        };

        assertThat(outcomeDesc).isEqualTo("ACTION:GENERAL_CHECK_IN");
    }

    @Test
    void rejectsUnknownOutcomeDiscriminator() {
        String json = """
                {
                    "outcome": "MALICIOUS_INJECTION",
                    "action": "REPEAT_PURCHASE_FOLLOW_UP",
                    "templateIntent": "REPEAT_PURCHASE",
                    "rationale": "test",
                    "confidence": 0.8
                }
                """;

        assertThatThrownBy(() -> objectMapper.readValue(json, RecommendationOutcome.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsUnknownSemanticActionInPayload() {
        String json = """
                {
                    "outcome": "ACTION",
                    "action": "SEND_UNRESTRICTED_MESSAGE",
                    "templateIntent": "REPEAT_PURCHASE",
                    "rationale": "test",
                    "confidence": 0.8
                }
                """;

        assertThatThrownBy(() -> objectMapper.readValue(json, RecommendationOutcome.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsUnknownTemplateIntentInPayload() {
        String json = """
                {
                    "outcome": "ACTION",
                    "action": "REPEAT_PURCHASE_FOLLOW_UP",
                    "templateIntent": "NON_EXISTENT_TEMPLATE",
                    "rationale": "test",
                    "confidence": 0.8
                }
                """;

        assertThatThrownBy(() -> objectMapper.readValue(json, RecommendationOutcome.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsOversizedRationale() {
        String oversizedRationale = "r".repeat(RecommendationOutcome.MAX_RATIONALE_LENGTH + 1);

        assertThatThrownBy(() -> new ActionRecommendation(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                oversizedRationale,
                RecommendationConfidence.of(0.8),
                DraftVariables.empty()
        )).isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Rationale exceeds maximum length of 500 characters");

        assertThatThrownBy(() -> new NoRecommendation(
                NoRecommendationReason.NO_RELEVANT_OFFER,
                oversizedRationale,
                RecommendationConfidence.of(0.8)
        )).isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Rationale exceeds maximum length of 500 characters");
    }

    @Test
    void rejectsNullOrBlankRationale() {
        assertThatThrownBy(() -> new ActionRecommendation(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "   ",
                RecommendationConfidence.of(0.8),
                DraftVariables.empty()
        )).isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Rationale is required");
    }

    @Test
    void rejectsNullActionOrTemplate() {
        assertThatThrownBy(() -> new ActionRecommendation(
                null,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "valid rationale",
                RecommendationConfidence.of(0.8),
                DraftVariables.empty()
        )).isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Action is required");

        assertThatThrownBy(() -> new ActionRecommendation(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                null,
                "valid rationale",
                RecommendationConfidence.of(0.8),
                DraftVariables.empty()
        )).isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Template intent is required");
    }
}
