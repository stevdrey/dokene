package io.github.stevdrey.dokene.ai.domain;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates strict, provider-neutral JSON Schemas representing {@link DraftOutcome} contracts.
 * Satisfies OpenAI Structured Outputs in {@code strict: true} mode.
 */
public final class DraftJsonSchema {
    public static final String SCHEMA_TITLE = "follow_up_message_draft";
    public static final String ROOT_PROPERTY = "draft";
    private static final ObjectMapper OBJECT_MAPPER = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final java.util.Set<String> ALLOWED_DRAFT_PROPERTIES = java.util.Set.of(
            "outcome", "action", "templateIntent", "body", "draftVariables", "locale",
            "evidence", "warnings", "rationale", "confidence"
    );
    private static final java.util.Set<String> ALLOWED_NO_DRAFT_PROPERTIES = java.util.Set.of(
            "outcome", "reason", "rationale", "confidence"
    );
    private static final java.util.Set<String> ALLOWED_DRAFT_VARIABLE_PROPERTIES = java.util.Set.of(
            "key", "value"
    );

    private DraftJsonSchema() {}

    /**
     * Generates the strict, provider-neutral JSON Schema for {@link DraftOutcome}.
     */
    public static Map<String, Object> generateSchema() {
        Map<String, Object> draftSchema = generateMessageDraftSchema();
        Map<String, Object> noDraftSchema = generateNoDraftSchema();

        Map<String, Object> draftProperty = new LinkedHashMap<>();
        draftProperty.put("anyOf", List.of(draftSchema, noDraftSchema));
        draftProperty.put("description", "Follow-up message draft outcome (MessageDraft or NoDraft)");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(ROOT_PROPERTY, draftProperty);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", SCHEMA_TITLE);
        root.put("type", "object");
        root.put("properties", properties);
        root.put("required", List.of(ROOT_PROPERTY));
        root.put("additionalProperties", false);
        return root;
    }

    /**
     * Parses a {@link DraftOutcome} from a JSON payload string.
     */
    public static DraftOutcome parseOutcome(String json) {
        return parseOutcome(json, OBJECT_MAPPER);
    }

    /**
     * Parses a {@link DraftOutcome} from a JSON payload string using a custom {@link ObjectMapper}.
     */
    public static DraftOutcome parseOutcome(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON payload cannot be null or blank");
        }
        Objects.requireNonNull(objectMapper, "ObjectMapper is required");
        try {
            ObjectMapper mapperToUse = objectMapper.rebuild()
                    .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .build();
            JsonNode rootNode = mapperToUse.readTree(json);
            if (rootNode == null || rootNode.isNull()) {
                throw new IllegalArgumentException("JSON payload cannot be null");
            }
            if (!rootNode.isObject()) {
                throw new IllegalArgumentException("JSON payload must be an object");
            }
            JsonNode targetNode;
            if (rootNode.has(ROOT_PROPERTY)) {
                for (String fieldName : rootNode.propertyNames()) {
                    if (!ROOT_PROPERTY.equals(fieldName)) {
                        throw new IllegalArgumentException("Unexpected property '" + fieldName + "' in schema envelope");
                    }
                }
                targetNode = rootNode.get(ROOT_PROPERTY);
            } else {
                targetNode = rootNode;
            }
            if (targetNode == null || targetNode.isNull()) {
                throw new IllegalArgumentException("Target draft node cannot be null");
            }
            if (!targetNode.isObject()) {
                throw new IllegalArgumentException("Target draft node must be an object");
            }
            validateAllowedProperties(targetNode);
            DraftOutcome outcome = mapperToUse.treeToValue(targetNode, DraftOutcome.class);
            if (outcome == null) {
                throw new IllegalArgumentException("Deserialized DraftOutcome cannot be null");
            }
            return outcome;
        } catch (RecommendationValidationException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof RecommendationValidationException rve) {
                    throw rve;
                }
                if (cause instanceof IllegalArgumentException iae) {
                    throw iae;
                }
                cause = cause.getCause();
            }
            throw new IllegalArgumentException("Failed to deserialize DraftOutcome from JSON payload", e);
        }
    }

    private static void validateAllowedProperties(JsonNode node) {
        if (!node.has("outcome")) {
            return;
        }
        String outcome = node.get("outcome").asText();
        java.util.Set<String> allowed = "DRAFT".equals(outcome)
                ? ALLOWED_DRAFT_PROPERTIES
                : "NO_DRAFT".equals(outcome)
                        ? ALLOWED_NO_DRAFT_PROPERTIES
                        : null;
        if (allowed != null) {
            for (String fieldName : node.propertyNames()) {
                if (!allowed.contains(fieldName)) {
                    throw new IllegalArgumentException("Unexpected property '" + fieldName + "' for outcome " + outcome);
                }
            }
        }
        if (node.has("rationale") && !node.get("rationale").isTextual()) {
            throw new IllegalArgumentException("Property 'rationale' must be a string");
        }
        if (node.has("confidence") && !node.get("confidence").isNumber()) {
            throw new IllegalArgumentException("Property 'confidence' must be a number");
        }
        if (node.has("confidence")) {
            BigDecimal confidence = node.get("confidence").decimalValue();
            if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new RecommendationValidationException("confidence", "Confidence must be between 0.0 and 1.0");
            }
        }
        if (node.has("action") && !node.get("action").isTextual()) {
            throw new IllegalArgumentException("Property 'action' must be a string");
        }
        if (node.has("templateIntent") && !node.get("templateIntent").isTextual()) {
            throw new IllegalArgumentException("Property 'templateIntent' must be a string");
        }
        if (node.has("body") && !node.get("body").isTextual()) {
            throw new IllegalArgumentException("Property 'body' must be a string");
        }
        if (node.has("locale") && !node.get("locale").isTextual()) {
            throw new IllegalArgumentException("Property 'locale' must be a string");
        }
        if (node.has("reason") && !node.get("reason").isTextual()) {
            throw new IllegalArgumentException("Property 'reason' must be a string");
        }
        if (node.has("evidence")) {
            JsonNode evNode = node.get("evidence");
            if (!evNode.isArray()) {
                throw new IllegalArgumentException("Property 'evidence' must be an array");
            }
            for (JsonNode item : evNode) {
                if (!item.isTextual()) {
                    throw new IllegalArgumentException("Evidence item must be a string");
                }
            }
        }
        if (node.has("warnings")) {
            JsonNode warnNode = node.get("warnings");
            if (!warnNode.isArray()) {
                throw new IllegalArgumentException("Property 'warnings' must be an array");
            }
            for (JsonNode item : warnNode) {
                if (!item.isTextual()) {
                    throw new IllegalArgumentException("Warning item must be a string");
                }
            }
        }
        if (node.has("draftVariables")) {
            JsonNode draftVarsNode = node.get("draftVariables");
            if (draftVarsNode != null) {
                if (!draftVarsNode.isArray()) {
                    throw new IllegalArgumentException("Property 'draftVariables' must be an array");
                }
                for (JsonNode item : draftVarsNode) {
                    if (!item.isObject()) {
                        throw new IllegalArgumentException("Draft variable item must be an object");
                    }
                    for (String fieldName : item.propertyNames()) {
                        if (!ALLOWED_DRAFT_VARIABLE_PROPERTIES.contains(fieldName)) {
                            throw new IllegalArgumentException("Unexpected property '" + fieldName + "' in draft variable entry");
                        }
                    }
                    if (item.has("key") && !item.get("key").isTextual()) {
                        throw new IllegalArgumentException("Draft variable key must be a string");
                    }
                    if (item.has("value") && !item.get("value").isTextual()) {
                        throw new IllegalArgumentException("Draft variable value must be a string");
                    }
                }
            }
        }
    }

    public static String generateSchemaJson() {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(generateSchema());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize draft JSON Schema", e);
        }
    }

    private static Map<String, Object> generateMessageDraftSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("outcome", Map.of(
                "type", "string",
                "enum", List.of("DRAFT"),
                "description", "Message draft discriminator"
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

        properties.put("body", Map.of(
                "type", "string",
                "minLength", 1,
                "maxLength", MessageDraft.MAX_BODY_LENGTH,
                "pattern", RecommendationRationale.NON_WHITESPACE_PATTERN,
                "description", "Concise, grounded follow-up message text (max " + MessageDraft.MAX_BODY_LENGTH + " chars)"
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
        draftVariablesProps.put("description", "List of template draft variables (max " + DraftVariables.MAX_ENTRIES + " entries)");
        properties.put("draftVariables", draftVariablesProps);

        properties.put("locale", Map.of(
                "type", "string",
                "minLength", 2,
                "maxLength", MessageDraft.MAX_LOCALE_LENGTH,
                "description", "Message language/locale identifier (e.g. es-419)"
        ));

        properties.put("evidence", Map.of(
                "type", "array",
                "maxItems", MessageDraft.MAX_METADATA_ITEMS,
                "items", Map.of("type", "string", "maxLength", MessageDraft.MAX_METADATA_ITEM_LENGTH,
                        "pattern", "^[^:\\n]+:\\s*\\S.*$"),
                "description", "Supporting context facts referenced in the draft. Each item MUST use the format 'Label: Value' (e.g. 'Compra: Café Molido'). Allowed labels: " + DraftGroundingContext.ALLOWED_LABELS_DESCRIPTION
        ));

        properties.put("warnings", Map.of(
                "type", "array",
                "maxItems", MessageDraft.MAX_METADATA_ITEMS,
                "items", Map.of("type", "string", "minLength", 1, "maxLength", MessageDraft.MAX_METADATA_ITEM_LENGTH,
                        "pattern", RecommendationRationale.NON_WHITESPACE_PATTERN),
                "description", "Caveats or warnings noticed during drafting"
        ));

        Map<String, Object> rationaleProps = new LinkedHashMap<>();
        rationaleProps.put("type", "string");
        rationaleProps.put("minLength", 1);
        rationaleProps.put("maxLength", DraftOutcome.MAX_RATIONALE_LENGTH);
        rationaleProps.put("pattern", RecommendationRationale.NON_WHITESPACE_PATTERN);
        rationaleProps.put("description", "Concise reasoning for the drafted wording (1 to " + DraftOutcome.MAX_RATIONALE_LENGTH + " chars)");
        properties.put("rationale", rationaleProps);

        properties.put("confidence", Map.of(
                "type", "number",
                "minimum", 0.0,
                "maximum", 1.0,
                "description", "Model confidence score between 0.0 and 1.0"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("outcome", "action", "templateIntent", "body", "draftVariables", "locale", "evidence", "warnings", "rationale", "confidence"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> generateNoDraftSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("outcome", Map.of(
                "type", "string",
                "enum", List.of("NO_DRAFT"),
                "description", "No-draft refusal discriminator"
        ));

        properties.put("reason", Map.of(
                "type", "string",
                "enum", Arrays.stream(NoDraftReason.values()).map(Enum::name).toList(),
                "description", "Reason for refusal to draft"
        ));

        Map<String, Object> rationaleProps = new LinkedHashMap<>();
        rationaleProps.put("type", "string");
        rationaleProps.put("minLength", 1);
        rationaleProps.put("maxLength", DraftOutcome.MAX_RATIONALE_LENGTH);
        rationaleProps.put("pattern", RecommendationRationale.NON_WHITESPACE_PATTERN);
        rationaleProps.put("description", "Concise explanation of why no draft was produced");
        properties.put("rationale", rationaleProps);

        properties.put("confidence", Map.of(
                "type", "number",
                "minimum", 0.0,
                "maximum", 1.0,
                "description", "Confidence score in no-draft decision between 0.0 and 1.0"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("outcome", "reason", "rationale", "confidence"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
