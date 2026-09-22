package io.github.stevdrey.dokene.recommendation.domain;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates strict JSON Schemas representing {@link RecommendationOutcome} contracts.
 * <p>
 * Specifically tailored for AI provider adapters using Structured Outputs (e.g. OpenAI JSON Schema mode),
 * enforcing {@code additionalProperties: false} on all object schemas, strict enum allowlists,
 * and explicit {@code required} arrays.
 */
public final class RecommendationJsonSchema {
    private static final String SCHEMA_NAME = "next_best_action_recommendation";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RecommendationJsonSchema() {}

    /**
     * Generates the strict JSON Schema for {@link RecommendationOutcome}.
     */
    public static Map<String, Object> generateSchema() {
        Map<String, Object> actionSchema = generateActionSchema();
        Map<String, Object> noRecommendationSchema = generateNoRecommendationSchema();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("anyOf", List.of(actionSchema, noRecommendationSchema));
        return root;
    }

    /**
     * Generates the full OpenAI Structured Outputs {@code response_format} configuration map.
     */
    public static Map<String, Object> generateResponseFormat() {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", SCHEMA_NAME);
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", generateSchema());

        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_schema");
        responseFormat.put("json_schema", jsonSchema);
        return responseFormat;
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
                "enum", List.of("ACTION")
        ));

        properties.put("action", Map.of(
                "type", "string",
                "enum", Arrays.stream(SemanticAction.values()).map(Enum::name).toList()
        ));

        properties.put("templateIntent", Map.of(
                "type", "string",
                "enum", Arrays.stream(SemanticTemplateIntent.values()).map(Enum::name).toList()
        ));

        properties.put("rationale", Map.of(
                "type", "string",
                "description", "Concise reasoning for the recommendation (max 500 chars)"
        ));

        properties.put("confidence", Map.of(
                "type", "number",
                "minimum", 0.0,
                "maximum", 1.0,
                "description", "Model confidence score between 0.0 and 1.0"
        ));

        Map<String, Object> variableItemProps = new LinkedHashMap<>();
        variableItemProps.put("key", Map.of("type", "string"));
        variableItemProps.put("value", Map.of("type", "string"));

        Map<String, Object> variableItem = new LinkedHashMap<>();
        variableItem.put("type", "object");
        variableItem.put("properties", variableItemProps);
        variableItem.put("required", List.of("key", "value"));
        variableItem.put("additionalProperties", false);

        properties.put("draftVariables", Map.of(
                "type", "array",
                "items", variableItem,
                "description", "List of template draft variables"
        ));

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
                "enum", List.of("NO_RECOMMENDATION")
        ));

        properties.put("reason", Map.of(
                "type", "string",
                "enum", Arrays.stream(NoRecommendationReason.values()).map(Enum::name).toList()
        ));

        properties.put("rationale", Map.of(
                "type", "string",
                "description", "Concise explanation of why no action was recommended (max 500 chars)"
        ));

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
