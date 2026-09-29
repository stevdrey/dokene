package io.github.stevdrey.dokene.ai.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DraftSafetyValidatorTest {

    @Test
    void allowsSafeGroundedDraft() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Juan, esperamos que estés disfrutando tu Café Tostado.",
                DraftVariables.of(Map.of("customer", "Juan")),
                "es-419",
                List.of("Café Tostado comprado hace 30 días"),
                List.of(),
                "Follow-up on recent coffee purchase",
                RecommendationConfidence.of(0.85)
        );

        String context = "Customer: Juan. Purchases: Café Tostado 500g.";
        Optional<String> violation = DraftSafetyValidator.validate(draft, context);
        assertThat(violation).isEmpty();
    }

    @Test
    void rejectsDraftWithExternalUrl() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Juan, visita https://tienda.example.com para tu compra.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );

        Optional<String> violation = DraftSafetyValidator.validate(draft, "Customer: Juan.");
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("unauthorized external link or URL");
    }

    @Test
    void rejectsDraftVariableWithUrl() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Juan, haz clic en el enlace.",
                DraftVariables.of(Map.of("link", "www.tienda.com")),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );

        Optional<String> violation = DraftSafetyValidator.validate(draft, "Customer: Juan.");
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("unauthorized external link or URL");
    }

    @Test
    void rejectsDraftWithProviderTemplateId() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Juan, usando meta_whatsapp_template_01 para ti.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );

        Optional<String> violation = DraftSafetyValidator.validate(draft, "Customer: Juan.");
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("unauthorized provider template identifier");
    }

    @Test
    void rejectsHallucinatedDiscountNotPresentInContext() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Juan, tienes un 20% de descuento en tu próxima orden.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );

        String contextWithoutDiscount = "Customer: Juan. Purchase: Café Molido.";
        Optional<String> violation = DraftSafetyValidator.validate(draft, contextWithoutDiscount);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("hallucinated offer, discount, or price term");
    }

    @Test
    void allowsDiscountTermWhenExplicitlyGroundedInContext() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Juan, aprovecha el descuento acordado en tu compra anterior.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );

        String contextWithDiscount = "Customer: Juan. Notes: Aplicar descuento acordado para cliente habitual.";
        Optional<String> violation = DraftSafetyValidator.validate(draft, contextWithDiscount);
        assertThat(violation).isEmpty();
    }
}
