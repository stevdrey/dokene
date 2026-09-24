package io.github.stevdrey.dokene.ai.domain;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates strict, provider-neutral JSON Schemas representing {@link RecommendationOutcome} contracts.
 * <p>
 * Satisfies OpenAI Structured Outputs in {@code strict: true} mode:
 * <ul>
 *   <li>The root schema is an object with {@code additionalProperties: false} (no root-level {@code anyOf}).</li>
 *   <li>The discriminated union is structured under the {@value #ROOT_PROPERTY} property, tying outcome-specific
 *       non-null required fields directly to the outcome discriminator ({@code ACTION} vs {@code NO_RECOMMENDATION}).</li>
 *   <li>Domain constraints are strictly encoded: {@code minLength}, {@code maxLength}, {@code pattern},
 *       {@code minItems}, {@code maxItems}, and enum allowlists.</li>
 * </ul>
 */
public final class RecommendationJsonSchema {
    public static final String SCHEMA_TITLE = "next_best_action_recommendation";
    public static final String ROOT_PROPERTY = "recommendation";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RecommendationJsonSchema() {}

    /**
     * Generates the strict, provider-neutral JSON Schema for {@link RecommendationOutcome}.
     */
    public static Map<String, Object> generateSchema() {
        Map<String, Object> actionSchema = generateActionSchema();
        Map<String, Object> noRecommendationSchema = generateNoRecommendationSchema();

        Map<String, Object> recommendationProperty = new LinkedHashMap<>();
        recommendationProperty.put("anyOf", List.of(actionSchema, noRecommendationSchema));
        recommendationProperty.put("description", "Next best action recommendation outcome (ActionRecommendation or NoRecommendation)");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(ROOT_PROPERTY, recommendationProperty);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", SCHEMA_TITLE);
        root.put("type", "object");
        root.put("properties", properties);
        root.put("required", List.of(ROOT_PROPERTY));
        root.put("additionalProperties", false);
        return root;
    }

    /**
     * Parses a {@link RecommendationOutcome} from a JSON payload string.
     * <p>
     * Supports both the wrapped schema envelope ({@code {"recommendation": {...}}})
     * and direct recommendation outcome JSON payloads.
     *
     * @param json the JSON payload string
     * @return the deserialized {@link RecommendationOutcome}
     */
    public static RecommendationOutcome parseOutcome(String json) {
        return parseOutcome(json, OBJECT_MAPPER);
    }

    /**
     * Parses a {@link RecommendationOutcome} from a JSON payload string using a custom {@link ObjectMapper}.
     *
     * @param json the JSON payload string
     * @param objectMapper the object mapper to use
     * @return the deserialized {@link RecommendationOutcome}
     */
    public static RecommendationOutcome parseOutcome(String json, ObjectMapper objectMapper) {
        try {
            JsonNode rootNode = objectMapper.readTree(json);
            JsonNode targetNode = rootNode.has(ROOT_PROPERTY) ? rootNode.get(ROOT_PROPERTY) : rootNode;
            return objectMapper.treeToValue(targetNode, RecommendationOutcome.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize RecommendationOutcome from JSON payload", e);
        }
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

    private static Map<String, Object> generateActionSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("outcome", Map.of(
                "type", "string",
                "enum", List.of("ACTION"),
                "description", "Action recommendation discriminator"
        ));

        properties.put("action", Map.of(
                "type", "string",
                "enum", Arrays.stream(SemanticAction.values()).map(Enum::name).toList(),
                "description", "Semantic follow-up action"
        ));

        properties.put("templateIntent", Map.of(
                "type", "string",
                "enum", Arrays.stream(SemanticTemplateIntent.values()).map(Enum::name).toList(),
                "description", "Semantic template intent"
        ));

        Map<String, Object> rationaleProps = new LinkedHashMap<>();
        rationaleProps.put("type", "string");
        rationaleProps.put("minLength", 1);
        rationaleProps.put("maxLength", RecommendationOutcome.MAX_RATIONALE_LENGTH);
        rationaleProps.put("description", "Concise reasoning for the recommendation (1 to "
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
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("outcome", "action", "templateIntent", "rationale", "confidence", "draftVariables"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> generateNoRecommendationSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("outcome", Map.of(
                "type", "string",
                "enum", List.of("NO_RECOMMENDATION"),
                "description", "No-recommendation refusal discriminator"
        ));

        properties.put("reason", Map.of(
                "type", "string",
                "enum", Arrays.stream(NoRecommendationReason.values()).map(Enum::name).toList(),
                "description", "Reason for refusal"
        ));

        Map<String, Object> rationaleProps = new LinkedHashMap<>();
        rationaleProps.put("type", "string");
        rationaleProps.put("minLength", 1);
        rationaleProps.put("maxLength", RecommendationOutcome.MAX_RATIONALE_LENGTH);
        rationaleProps.put("description", "Concise explanation of why no action was recommended (1 to "
                + RecommendationOutcome.MAX_RATIONALE_LENGTH + " characters)");
        properties.put("rationale", rationaleProps);

        properties.put("confidence", Map.of(
                "type", "number",
                "minimum", 0.0,
                "maximum", 1.0,
                "description", "Confidence score in no-action decision between 0.0 and 1.0"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("outcome", "reason", "rationale", "confidence"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
