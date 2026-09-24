package io.github.stevdrey.dokene.ai.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationJsonSchemaTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void generatesStrictOpenAiCompatibleRootObjectSchemaWithDiscriminatedUnion() {
        Map<String, Object> root = RecommendationJsonSchema.generateSchema();
        assertThat(root.get("title")).isEqualTo(RecommendationJsonSchema.SCHEMA_TITLE);
        assertThat(root.get("type")).isEqualTo("object");
        assertThat(root.get("additionalProperties")).isEqualTo(false);
        assertThat(root).doesNotContainKey("anyOf");

        List<String> rootRequired = (List<String>) root.get("required");
        assertThat(rootRequired).containsExactly(RecommendationJsonSchema.ROOT_PROPERTY);

        Map<String, Object> rootProperties = (Map<String, Object>) root.get("properties");
        assertThat(rootProperties).containsKey(RecommendationJsonSchema.ROOT_PROPERTY);

        Map<String, Object> recProp = (Map<String, Object>) rootProperties.get(RecommendationJsonSchema.ROOT_PROPERTY);
        List<Map<String, Object>> anyOf = (List<Map<String, Object>>) recProp.get("anyOf");
        assertThat(anyOf).hasSize(2);

        // Branch 0: ACTION
        Map<String, Object> actionBranch = anyOf.get(0);
        assertThat(actionBranch.get("type")).isEqualTo("object");
        assertThat(actionBranch.get("additionalProperties")).isEqualTo(false);
        List<String> actionRequired = (List<String>) actionBranch.get("required");
        assertThat(actionRequired).containsExactlyInAnyOrder(
                "outcome", "action", "templateIntent", "rationale", "confidence", "draftVariables"
        );

        Map<String, Object> actionProperties = (Map<String, Object>) actionBranch.get("properties");
        assertThat(actionProperties).doesNotContainKey("reason");

        Map<String, Object> actionOutcomeProp = (Map<String, Object>) actionProperties.get("outcome");
        assertThat(actionOutcomeProp.get("type")).isEqualTo("string");
        assertThat((List<String>) actionOutcomeProp.get("enum")).containsExactly("ACTION");

        Map<String, Object> actionFieldProp = (Map<String, Object>) actionProperties.get("action");
        assertThat(actionFieldProp.get("type")).isEqualTo("string");
        List<String> actionEnums = (List<String>) actionFieldProp.get("enum");
        assertThat(actionEnums).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(SemanticAction.values()).map(Enum::name).toList()
        );
        assertThat(actionEnums).doesNotContainNull();

        Map<String, Object> templateFieldProp = (Map<String, Object>) actionProperties.get("templateIntent");
        assertThat(templateFieldProp.get("type")).isEqualTo("string");
        List<String> templateEnums = (List<String>) templateFieldProp.get("enum");
        assertThat(templateEnums).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(SemanticTemplateIntent.values()).map(Enum::name).toList()
        );
        assertThat(templateEnums).doesNotContainNull();

        Map<String, Object> actionRationaleProp = (Map<String, Object>) actionProperties.get("rationale");
        assertThat(actionRationaleProp.get("type")).isEqualTo("string");
        assertThat(actionRationaleProp.get("minLength")).isEqualTo(1);
        assertThat(actionRationaleProp.get("maxLength")).isEqualTo(RecommendationOutcome.MAX_RATIONALE_LENGTH);

        Map<String, Object> actionConfidenceProp = (Map<String, Object>) actionProperties.get("confidence");
        assertThat(actionConfidenceProp.get("type")).isEqualTo("number");
        assertThat(actionConfidenceProp.get("minimum")).isEqualTo(0.0);
        assertThat(actionConfidenceProp.get("maximum")).isEqualTo(1.0);

        Map<String, Object> draftVarsProp = (Map<String, Object>) actionProperties.get("draftVariables");
        assertThat(draftVarsProp.get("type")).isEqualTo("array");
        assertThat(draftVarsProp.get("maxItems")).isEqualTo(DraftVariables.MAX_ENTRIES);

        Map<String, Object> itemSchema = (Map<String, Object>) draftVarsProp.get("items");
        assertThat(itemSchema.get("type")).isEqualTo("object");
        assertThat(itemSchema.get("additionalProperties")).isEqualTo(false);
        assertThat((List<String>) itemSchema.get("required")).containsExactlyInAnyOrder("key", "value");

        Map<String, Object> itemProps = (Map<String, Object>) itemSchema.get("properties");
        Map<String, Object> keyProp = (Map<String, Object>) itemProps.get("key");
        assertThat(keyProp.get("pattern")).isEqualTo("^[a-zA-Z0-9_]{1,50}$");
        assertThat(keyProp.get("maxLength")).isEqualTo(DraftVariableEntry.MAX_KEY_LENGTH);

        Map<String, Object> valueProp = (Map<String, Object>) itemProps.get("value");
        assertThat(valueProp.get("maxLength")).isEqualTo(DraftVariableEntry.MAX_VALUE_LENGTH);

        // Branch 1: NO_RECOMMENDATION
        Map<String, Object> noRecBranch = anyOf.get(1);
        assertThat(noRecBranch.get("type")).isEqualTo("object");
        assertThat(noRecBranch.get("additionalProperties")).isEqualTo(false);
        List<String> noRecRequired = (List<String>) noRecBranch.get("required");
        assertThat(noRecRequired).containsExactlyInAnyOrder("outcome", "reason", "rationale", "confidence");

        Map<String, Object> noRecProperties = (Map<String, Object>) noRecBranch.get("properties");
        assertThat(noRecProperties).doesNotContainKeys("action", "templateIntent", "draftVariables");

        Map<String, Object> noRecOutcomeProp = (Map<String, Object>) noRecProperties.get("outcome");
        assertThat(noRecOutcomeProp.get("type")).isEqualTo("string");
        assertThat((List<String>) noRecOutcomeProp.get("enum")).containsExactly("NO_RECOMMENDATION");

        Map<String, Object> reasonProp = (Map<String, Object>) noRecProperties.get("reason");
        assertThat(reasonProp.get("type")).isEqualTo("string");
        List<String> reasonEnums = (List<String>) reasonProp.get("enum");
        assertThat(reasonEnums).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(NoRecommendationReason.values()).map(Enum::name).toList()
        );
        assertThat(reasonEnums).doesNotContainNull();

        Map<String, Object> noRecRationaleProp = (Map<String, Object>) noRecProperties.get("rationale");
        assertThat(noRecRationaleProp.get("type")).isEqualTo("string");
        assertThat(noRecRationaleProp.get("minLength")).isEqualTo(1);
        assertThat(noRecRationaleProp.get("maxLength")).isEqualTo(RecommendationOutcome.MAX_RATIONALE_LENGTH);

        Map<String, Object> noRecConfidenceProp = (Map<String, Object>) noRecProperties.get("confidence");
        assertThat(noRecConfidenceProp.get("type")).isEqualTo("number");
        assertThat(noRecConfidenceProp.get("minimum")).isEqualTo(0.0);
        assertThat(noRecConfidenceProp.get("maximum")).isEqualTo(1.0);
    }

    @Test
    void deserializesWrappedSchemaEnvelopes() {
        String wrappedActionJson = """
                {
                    "recommendation": {
                        "outcome": "ACTION",
                        "action": "REPEAT_PURCHASE_FOLLOW_UP",
                        "templateIntent": "REPEAT_PURCHASE",
                        "rationale": "Cadence threshold reached",
                        "confidence": 0.88,
                        "draftVariables": [{"key": "item", "value": "Coffee"}]
                    }
                }
                """;

        RecommendationOutcome actionOutcome = RecommendationJsonSchema.parseOutcome(wrappedActionJson);
        assertThat(actionOutcome).isInstanceOf(ActionRecommendation.class);
        ActionRecommendation action = (ActionRecommendation) actionOutcome;
        assertThat(action.action()).isEqualTo(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP);
        assertThat(action.draftVariables().asMap()).containsEntry("item", "Coffee");

        String wrappedNoRecJson = """
                {
                    "recommendation": {
                        "outcome": "NO_RECOMMENDATION",
                        "reason": "INSUFFICIENT_HISTORY",
                        "rationale": "Only one purchase recorded",
                        "confidence": 0.95
                    }
                }
                """;

        RecommendationOutcome noRecOutcome = RecommendationJsonSchema.parseOutcome(wrappedNoRecJson);
        assertThat(noRecOutcome).isInstanceOf(NoRecommendation.class);
        NoRecommendation noRec = (NoRecommendation) noRecOutcome;
        assertThat(noRec.reason()).isEqualTo(NoRecommendationReason.INSUFFICIENT_HISTORY);
    }

    @Test
    void deserializesRawPayloadDirectly() throws Exception {
        String actionJson = """
                {
                    "outcome": "ACTION",
                    "action": "REPEAT_PURCHASE_FOLLOW_UP",
                    "templateIntent": "REPEAT_PURCHASE",
                    "rationale": "Cadence threshold reached",
                    "confidence": 0.88,
                    "draftVariables": [{"key": "item", "value": "Coffee"}]
                }
                """;

        RecommendationOutcome actionOutcome = RecommendationJsonSchema.parseOutcome(actionJson);
        assertThat(actionOutcome).isInstanceOf(ActionRecommendation.class);

        // Also works with direct Jackson readValue
        RecommendationOutcome direct = objectMapper.readValue(actionJson, RecommendationOutcome.class);
        assertThat(direct).isInstanceOf(ActionRecommendation.class);
    }

    @Test
    void parseOutcomeFailsOnMalformedJson() {
        assertThatThrownBy(() -> RecommendationJsonSchema.parseOutcome("{invalid-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize RecommendationOutcome");
    }

    @Test
    void generatesValidPrettyJsonString() throws Exception {
        String schemaJson = RecommendationJsonSchema.generateSchemaJson();
        assertThat(schemaJson).isNotBlank();

        JsonNode tree = objectMapper.readTree(schemaJson);
        assertThat(tree.get("title").asString()).isEqualTo(RecommendationJsonSchema.SCHEMA_TITLE);
        assertThat(tree.get("type").asString()).isEqualTo("object");
        assertThat(tree.get("additionalProperties").asBoolean()).isFalse();
        assertThat(tree.get("required").get(0).asString()).isEqualTo(RecommendationJsonSchema.ROOT_PROPERTY);
    }
}
