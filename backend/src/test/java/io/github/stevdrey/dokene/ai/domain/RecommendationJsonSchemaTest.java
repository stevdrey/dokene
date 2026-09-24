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
    void generatesStrictOpenAiCompatibleSchema() {
        Map<String, Object> schema = RecommendationJsonSchema.generateSchema();
        assertThat(schema.get("title")).isEqualTo(RecommendationJsonSchema.SCHEMA_TITLE);
        assertThat(schema.get("type")).isEqualTo("object");

        List<Map<String, Object>> anyOf = (List<Map<String, Object>>) schema.get("anyOf");
        assertThat(anyOf).hasSize(2);

        for (Map<String, Object> branch : anyOf) {
            assertThat(branch.get("type")).isEqualTo("object");
            assertThat(branch.get("additionalProperties")).isEqualTo(false);

            Map<String, Object> properties = (Map<String, Object>) branch.get("properties");
            List<String> required = (List<String>) branch.get("required");

            // In strict structured outputs, every property defined in properties must be in required
            assertThat(required).containsExactlyInAnyOrderElementsOf(properties.keySet());
        }

        // Action branch checks
        Map<String, Object> actionBranch = anyOf.get(0);
        Map<String, Object> actionProps = (Map<String, Object>) actionBranch.get("properties");

        Map<String, Object> actionField = (Map<String, Object>) actionProps.get("action");
        List<String> actionEnums = (List<String>) actionField.get("enum");
        assertThat(actionEnums).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(SemanticAction.values()).map(Enum::name).toList()
        );

        Map<String, Object> templateField = (Map<String, Object>) actionProps.get("templateIntent");
        List<String> templateEnums = (List<String>) templateField.get("enum");
        assertThat(templateEnums).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(SemanticTemplateIntent.values()).map(Enum::name).toList()
        );

        // NoRecommendation branch checks
        Map<String, Object> noRecBranch = anyOf.get(1);
        Map<String, Object> noRecProps = (Map<String, Object>) noRecBranch.get("properties");

        Map<String, Object> reasonField = (Map<String, Object>) noRecProps.get("reason");
        List<String> reasonEnums = (List<String>) reasonField.get("enum");
        assertThat(reasonEnums).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(NoRecommendationReason.values()).map(Enum::name).toList()
        );
    }

    @Test
    void generatesValidPrettyJsonString() throws Exception {
        String schemaJson = RecommendationJsonSchema.generateSchemaJson();
        assertThat(schemaJson).isNotBlank();

        JsonNode tree = objectMapper.readTree(schemaJson);
        assertThat(tree.get("title").asString()).isEqualTo(RecommendationJsonSchema.SCHEMA_TITLE);
        assertThat(tree.get("type").asString()).isEqualTo("object");
        assertThat(tree.has("anyOf")).isTrue();
    }
}
