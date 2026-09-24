package io.github.stevdrey.dokene.ai.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates strict, provider-neutral JSON Schemas representing {@link RecommendationOutcome} contracts.
 * <p>
 * Reshapes the recommendation contract as a single valid root object compatible with OpenAI Structured
 * Outputs in {@code strict: true} mode:
 * <ul>
 *   <li>The root schema is an object with {@code additionalProperties: false} (no root-level {@code anyOf}).</li>
 *   <li>All properties are declared in the {@code required} array.</li>
 *   <li>Domain constraints are strictly encoded: {@code minLength}, {@code maxLength}, {@code pattern},
 *       {@code minItems}, {@code maxItems}, and enum allowlists.</li>
 * </ul>
 */
public final class RecommendationJsonSchema {
    public static final String SCHEMA_TITLE = "next_best_action_recommendation";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RecommendationJsonSchema() {}

    /**
     * Generates the strict, provider-neutral JSON Schema for {@link RecommendationOutcome}.
     */
    public static Map<String, Object> generateSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("outcome", Map.of(
                "type", "string",
                "enum", List.of("ACTION", "NO_RECOMMENDATION"),
                "description", "Recommendation outcome type: ACTION or NO_RECOMMENDATION"
        ));

        List<Object> actionEnums = new ArrayList<>();
        actionEnums.addAll(Arrays.stream(SemanticAction.values()).map(Enum::name).toList());
        actionEnums.add(null);
        properties.put("action", Map.of(
                "type", List.of("string", "null"),
                "enum", actionEnums,
                "description", "Semantic follow-up action if outcome is ACTION, or null if NO_RECOMMENDATION"
        ));

        List<Object> templateEnums = new ArrayList<>();
        templateEnums.addAll(Arrays.stream(SemanticTemplateIntent.values()).map(Enum::name).toList());
        templateEnums.add(null);
        properties.put("templateIntent", Map.of(
                "type", List.of("string", "null"),
                "enum", templateEnums,
                "description", "Semantic template intent if outcome is ACTION, or null if NO_RECOMMENDATION"
        ));

        List<Object> reasonEnums = new ArrayList<>();
        reasonEnums.addAll(Arrays.stream(NoRecommendationReason.values()).map(Enum::name).toList());
        reasonEnums.add(null);
        properties.put("reason", Map.of(
                "type", List.of("string", "null"),
                "enum", reasonEnums,
                "description", "Reason for refusal if outcome is NO_RECOMMENDATION, or null if ACTION"
        ));

        Map<String, Object> rationaleProps = new LinkedHashMap<>();
        rationaleProps.put("type", "string");
        rationaleProps.put("minLength", 1);
        rationaleProps.put("maxLength", RecommendationOutcome.MAX_RATIONALE_LENGTH);
        rationaleProps.put("description", "Concise reasoning for the recommendation or refusal (1 to "
                + RecommendationOutcome.MAX_RATIONALE_LENGTH + " characters)");
        properties.put("rationale", rationaleProps);

        properties.put("confidence", Map.of(
                "type", "number",
                "minimum", 0.0,
                "maximum", 1.0,
                "description", "Model confidence score between 0.0 and 1.0"
        ));

        Map<String, Object> variableKeyProps = new LinkedHashMap<>();
        variableKeyProps.put("type", "string");
        variableKeyProps.put("minLength", 1);
        variableKeyProps.put("maxLength", DraftVariableEntry.MAX_KEY_LENGTH);
        variableKeyProps.put("pattern", "^[a-zA-Z0-9_]{1,50}$");

        Map<String, Object> variableValueProps = new LinkedHashMap<>();
        variableValueProps.put("type", "string");
        variableValueProps.put("maxLength", DraftVariableEntry.MAX_VALUE_LENGTH);

        Map<String, Object> variableItemProps = new LinkedHashMap<>();
        variableItemProps.put("key", variableKeyProps);
        variableItemProps.put("value", variableValueProps);

        Map<String, Object> variableItem = new LinkedHashMap<>();
        variableItem.put("type", "object");
        variableItem.put("properties", variableItemProps);
        variableItem.put("required", List.of("key", "value"));
        variableItem.put("additionalProperties", false);

        Map<String, Object> draftVariablesProps = new LinkedHashMap<>();
        draftVariablesProps.put("type", "array");
        draftVariablesProps.put("maxItems", DraftVariables.MAX_ENTRIES);
        draftVariablesProps.put("items", variableItem);
        draftVariablesProps.put("description", "List of template draft variables (max "
                + DraftVariables.MAX_ENTRIES + " entries)");
        properties.put("draftVariables", draftVariablesProps);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("title", SCHEMA_TITLE);
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("outcome", "action", "templateIntent", "reason", "rationale", "confidence", "draftVariables"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * Returns the schema serialized as a formatted JSON string.
     */
    public static String generateSchemaJson() {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(generateSchema());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize recommendation JSON Schema", e);
        }
    }
}
