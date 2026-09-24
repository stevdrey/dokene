package io.github.stevdrey.dokene.ai.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationJsonSchemaTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void generatesStrictOpenAiCompatibleRootObjectSchema() {
        Map<String, Object> schema = RecommendationJsonSchema.generateSchema();
        assertThat(schema.get("title")).isEqualTo(RecommendationJsonSchema.SCHEMA_TITLE);
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        assertThat(schema).doesNotContainKey("anyOf");

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        List<String> required = (List<String>) schema.get("required");

        // In strict mode, every property defined in properties must be in required
        assertThat(required).containsExactlyInAnyOrderElementsOf(properties.keySet());

        // Outcome property checks
        Map<String, Object> outcomeProp = (Map<String, Object>) properties.get("outcome");
        assertThat(outcomeProp.get("type")).isEqualTo("string");
        assertThat((List<String>) outcomeProp.get("enum")).containsExactly("ACTION", "NO_RECOMMENDATION");

        // Action property checks
        Map<String, Object> actionProp = (Map<String, Object>) properties.get("action");
        assertThat((List<String>) actionProp.get("type")).containsExactly("string", "null");
        List<Object> actionEnums = (List<Object>) actionProp.get("enum");
        assertThat(actionEnums).containsAll(Arrays.stream(SemanticAction.values()).map(Enum::name).toList());
        assertThat(actionEnums).containsNull();

        // TemplateIntent property checks
        Map<String, Object> templateProp = (Map<String, Object>) properties.get("templateIntent");
        assertThat((List<String>) templateProp.get("type")).containsExactly("string", "null");
        List<Object> templateEnums = (List<Object>) templateProp.get("enum");
        assertThat(templateEnums).containsAll(Arrays.stream(SemanticTemplateIntent.values()).map(Enum::name).toList());
        assertThat(templateEnums).containsNull();

        // Reason property checks
        Map<String, Object> reasonProp = (Map<String, Object>) properties.get("reason");
        assertThat((List<String>) reasonProp.get("type")).containsExactly("string", "null");
        List<Object> reasonEnums = (List<Object>) reasonProp.get("enum");
        assertThat(reasonEnums).containsAll(Arrays.stream(NoRecommendationReason.values()).map(Enum::name).toList());
        assertThat(reasonEnums).containsNull();

        // Rationale property checks
        Map<String, Object> rationaleProp = (Map<String, Object>) properties.get("rationale");
        assertThat(rationaleProp.get("type")).isEqualTo("string");
        assertThat(rationaleProp.get("minLength")).isEqualTo(1);
        assertThat(rationaleProp.get("maxLength")).isEqualTo(RecommendationOutcome.MAX_RATIONALE_LENGTH);

        // Confidence property checks
        Map<String, Object> confidenceProp = (Map<String, Object>) properties.get("confidence");
        assertThat(confidenceProp.get("type")).isEqualTo("number");
        assertThat(confidenceProp.get("minimum")).isEqualTo(0.0);
        assertThat(confidenceProp.get("maximum")).isEqualTo(1.0);

        // DraftVariables property checks
        Map<String, Object> draftVarsProp = (Map<String, Object>) properties.get("draftVariables");
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
    }

    @Test
    void deserializesSchemaConformingJsonPayloads() throws Exception {
        String actionJson = """
                {
                    "outcome": "ACTION",
                    "action": "REPEAT_PURCHASE_FOLLOW_UP",
                    "templateIntent": "REPEAT_PURCHASE",
                    "reason": null,
                    "rationale": "Cadence threshold reached",
                    "confidence": 0.88,
                    "draftVariables": [{"key": "item", "value": "Coffee"}]
                }
                """;

        RecommendationOutcome actionOutcome = objectMapper.readValue(actionJson, RecommendationOutcome.class);
        assertThat(actionOutcome).isInstanceOf(ActionRecommendation.class);
        ActionRecommendation action = (ActionRecommendation) actionOutcome;
        assertThat(action.action()).isEqualTo(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP);
        assertThat(action.draftVariables().asMap()).containsEntry("item", "Coffee");

        String noRecJson = """
                {
                    "outcome": "NO_RECOMMENDATION",
                    "action": null,
                    "templateIntent": null,
                    "reason": "INSUFFICIENT_HISTORY",
                    "rationale": "Only one purchase recorded",
                    "confidence": 0.95,
                    "draftVariables": []
                }
                """;

        RecommendationOutcome noRecOutcome = objectMapper.readValue(noRecJson, RecommendationOutcome.class);
        assertThat(noRecOutcome).isInstanceOf(NoRecommendation.class);
        NoRecommendation noRec = (NoRecommendation) noRecOutcome;
        assertThat(noRec.reason()).isEqualTo(NoRecommendationReason.INSUFFICIENT_HISTORY);
    }

    @Test
    void generatesValidPrettyJsonString() throws Exception {
        String schemaJson = RecommendationJsonSchema.generateSchemaJson();
        assertThat(schemaJson).isNotBlank();

        JsonNode tree = objectMapper.readTree(schemaJson);
        assertThat(tree.get("title").asString()).isEqualTo(RecommendationJsonSchema.SCHEMA_TITLE);
        assertThat(tree.get("type").asString()).isEqualTo("object");
        assertThat(tree.get("additionalProperties").asBoolean()).isFalse();
    }
}
