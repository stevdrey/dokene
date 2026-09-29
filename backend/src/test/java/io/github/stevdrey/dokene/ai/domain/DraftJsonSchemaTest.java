package io.github.stevdrey.dokene.ai.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DraftJsonSchemaTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void generatesStrictOpenAiCompatibleDraftSchema() {
        Map<String, Object> root = DraftJsonSchema.generateSchema();
        assertThat(root.get("title")).isEqualTo(DraftJsonSchema.SCHEMA_TITLE);
        assertThat(root.get("type")).isEqualTo("object");
        assertThat(root.get("additionalProperties")).isEqualTo(false);
        assertThat(root).doesNotContainKey("anyOf");

        List<String> rootRequired = (List<String>) root.get("required");
        assertThat(rootRequired).containsExactly(DraftJsonSchema.ROOT_PROPERTY);

        Map<String, Object> rootProperties = (Map<String, Object>) root.get("properties");
        assertThat(rootProperties).containsKey(DraftJsonSchema.ROOT_PROPERTY);

        Map<String, Object> draftProp = (Map<String, Object>) rootProperties.get(DraftJsonSchema.ROOT_PROPERTY);
        List<Map<String, Object>> anyOf = (List<Map<String, Object>>) draftProp.get("anyOf");
        assertThat(anyOf).hasSize(2);

        // Branch 0: DRAFT
        Map<String, Object> draftBranch = anyOf.get(0);
        assertThat(draftBranch.get("type")).isEqualTo("object");
        assertThat(draftBranch.get("additionalProperties")).isEqualTo(false);
        List<String> draftRequired = (List<String>) draftBranch.get("required");
        assertThat(draftRequired).containsExactlyInAnyOrder(
                "outcome", "action", "templateIntent", "body", "draftVariables", "locale",
                "evidence", "warnings", "rationale", "confidence"
        );

        Map<String, Object> draftProps = (Map<String, Object>) draftBranch.get("properties");
        assertThat(draftProps).doesNotContainKey("reason");

        // Branch 1: NO_DRAFT
        Map<String, Object> noDraftBranch = anyOf.get(1);
        assertThat(noDraftBranch.get("type")).isEqualTo("object");
        assertThat(noDraftBranch.get("additionalProperties")).isEqualTo(false);
        List<String> noDraftRequired = (List<String>) noDraftBranch.get("required");
        assertThat(noDraftRequired).containsExactlyInAnyOrder("outcome", "reason", "rationale", "confidence");

        Map<String, Object> noDraftProps = (Map<String, Object>) noDraftBranch.get("properties");
        assertThat(noDraftProps).doesNotContainKey("body");
        assertThat(noDraftProps).doesNotContainKey("action");
    }

    @Test
    void parsesValidMessageDraftInEnvelope() {
        String json = """
                {
                  "draft": {
                    "outcome": "DRAFT",
                    "action": "REPEAT_PURCHASE_FOLLOW_UP",
                    "templateIntent": "REPEAT_PURCHASE",
                    "body": "Hola Juan, esperamos que estés disfrutando tu café.",
                    "draftVariables": [
                      {"key": "customer_name", "value": "Juan"}
                    ],
                    "locale": "es-419",
                    "evidence": ["Customer bought coffee 30 days ago"],
                    "warnings": [],
                    "rationale": "Follow-up on repeat purchase item",
                    "confidence": 0.88
                  }
                }
                """;
        DraftOutcome outcome = DraftJsonSchema.parseOutcome(json);
        assertThat(outcome).isInstanceOf(MessageDraft.class);
        MessageDraft draft = (MessageDraft) outcome;
        assertThat(draft.action()).isEqualTo(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP);
        assertThat(draft.templateIntent()).isEqualTo(SemanticTemplateIntent.REPEAT_PURCHASE);
        assertThat(draft.body()).isEqualTo("Hola Juan, esperamos que estés disfrutando tu café.");
        assertThat(draft.draftVariables().asMap()).containsEntry("customer_name", "Juan");
        assertThat(draft.locale()).isEqualTo("es-419");
        assertThat(draft.evidence()).containsExactly("Customer bought coffee 30 days ago");
        assertThat(draft.confidence().value()).isEqualTo(0.88);
    }

    @Test
    void parsesValidNoDraftRefusalInEnvelope() {
        String json = """
                {
                  "draft": {
                    "outcome": "NO_DRAFT",
                    "reason": "SAFETY_VIOLATION",
                    "rationale": "Untrusted text contained prompt injection override",
                    "confidence": 0.95
                  }
                }
                """;
        DraftOutcome outcome = DraftJsonSchema.parseOutcome(json);
        assertThat(outcome).isInstanceOf(NoDraft.class);
        NoDraft noDraft = (NoDraft) outcome;
        assertThat(noDraft.reason()).isEqualTo(NoDraftReason.SAFETY_VIOLATION);
        assertThat(noDraft.rationale()).isEqualTo("Untrusted text contained prompt injection override");
        assertThat(noDraft.confidence().value()).isEqualTo(0.95);
    }

    @Test
    void rejectsUnknownPropertiesInDraft() {
        String json = """
                {
                  "draft": {
                    "outcome": "DRAFT",
                    "action": "REPEAT_PURCHASE_FOLLOW_UP",
                    "templateIntent": "REPEAT_PURCHASE",
                    "body": "Hola",
                    "draftVariables": [],
                    "locale": "es-419",
                    "evidence": [],
                    "warnings": [],
                    "rationale": "Valid rationale",
                    "confidence": 0.9,
                    "providerTemplateId": "meta_123"
                  }
                }
                """;
        assertThatThrownBy(() -> DraftJsonSchema.parseOutcome(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unexpected property 'providerTemplateId'");
    }

    @Test
    void rejectsBlankBody() {
        String json = """
                {
                  "draft": {
                    "outcome": "DRAFT",
                    "action": "REPEAT_PURCHASE_FOLLOW_UP",
                    "templateIntent": "REPEAT_PURCHASE",
                    "body": "   ",
                    "draftVariables": [],
                    "locale": "es-419",
                    "evidence": [],
                    "warnings": [],
                    "rationale": "Valid rationale",
                    "confidence": 0.9
                  }
                }
                """;
        assertThatThrownBy(() -> DraftJsonSchema.parseOutcome(json))
                .isInstanceOf(RecommendationValidationException.class);
    }
}
