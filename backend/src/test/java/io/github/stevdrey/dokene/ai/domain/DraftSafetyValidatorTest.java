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

    @Test
    void rejectsDraftWithVariousUriSchemesAndDomainLinks() {
        MessageDraft draftFtp = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Visita ftp://files.example.com para tu catálogo.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );
        assertThat(DraftSafetyValidator.validate(draftFtp, "Customer: Juan.")).isPresent();

        MessageDraft draftDomain = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Entra a micatalogo.cr ahora.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );
        assertThat(DraftSafetyValidator.validate(draftDomain, "Customer: Juan.")).isPresent();
    }

    @Test
    void rejectsDraftWithProviderTemplateVariants() {
        MessageDraft draftHsm = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Enviando hsm_id_promo_2026 para ti.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );
        assertThat(DraftSafetyValidator.validate(draftHsm, "Customer: Juan.")).isPresent();

        MessageDraft draftWaba = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Plantilla waba_cart_reminder activada.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );
        assertThat(DraftSafetyValidator.validate(draftWaba, "Customer: Juan.")).isPresent();
    }

    @Test
    void rejectsDiscrepantDiscountPercentageEvenWhenTermIsPresent() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Tienes un 50% de descuento en tu próxima compra.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );

        // Context only mentions 5% discount
        String context = "Customer: Juan. Notes: Cliente tiene 5% de descuento asignado.";
        Optional<String> violation = DraftSafetyValidator.validate(draft, context);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("not grounded in context: '50%'");
    }

    @Test
    void allowsMatchingDiscountPercentageGroundedInContext() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Aprovecha tu 15% de descuento especial.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );

        String context = "Customer: Juan. Notes: Se le ofrece 15% de descuento.";
        Optional<String> violation = DraftSafetyValidator.validate(draft, context);
        assertThat(violation).isEmpty();
    }

    @Test
    void rejectsDiscrepantCurrencyAmountEvenWhenCurrencyIsPresent() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "El saldo pendiente es de $250.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );

        // Context mentions $100
        String context = "Customer: Juan. Saldo pendiente: $100.";
        Optional<String> violation = DraftSafetyValidator.validate(draft, context);
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("not grounded in context");
    }

    @Test
    void rejectsDraftWithUngroundedOrUnsafeEvidence() {
        MessageDraft draftUrlInEvidence = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Juan, gracias por tu compra.",
                DraftVariables.empty(),
                "es-419",
                List.of("Compra: https://phishing.com/receipt"),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );
        assertThat(DraftSafetyValidator.validate(draftUrlInEvidence, "Customer: Juan.")).isPresent();

        MessageDraft draftUngroundedEvidence = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Juan, gracias por tu compra.",
                DraftVariables.empty(),
                "es-419",
                List.of("Producto: Maquinaria Pesada XYZ-9000"),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );
        Optional<String> violation = DraftSafetyValidator.validate(draftUngroundedEvidence, "Customer: Juan. Compró Café.");
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("evidence item contains factual claim not found in context");
    }
}
