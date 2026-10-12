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
                List.of("Compra reciente: Café Tostado 500g"),
                List.of(),
                "Follow-up on recent coffee purchase",
                RecommendationConfidence.of(0.85)
        );

        String context = "Customer: Juan. Purchases: Café Tostado 500g.";
        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, context);
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

        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, "Customer: Juan.");
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.UNAUTHORIZED_URL);
        assertThat(violation.get().description()).contains("unauthorized external link or URL");
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

        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, "Customer: Juan.");
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.UNAUTHORIZED_URL);
        assertThat(violation.get().description()).contains("unauthorized external link or URL");
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

        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, "Customer: Juan.");
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.UNAUTHORIZED_PROVIDER_TEMPLATE);
        assertThat(violation.get().description()).contains("unauthorized provider template identifier");
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
        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, contextWithoutDiscount);
        assertThat(violation).isPresent();
        assertThat(violation.get().description()).contains("hallucinated");
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
        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, contextWithDiscount);
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
        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, context);
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_PERCENTAGE);
        assertThat(violation.get().description()).contains("not grounded in context: '50%'");
    }

    @Test
    void rejectsSubstitutedPercentageEvenIfContextHasLongerPercentage() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Tienes un 5% de descuento en tu orden.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );

        // Context contains 15%, which substring '5%' should NOT match
        String context = "Customer: Juan. Notes: Cliente tiene 15% de descuento asignado.";
        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, context);
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_PERCENTAGE);
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
        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, context);
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
        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, context);
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_PRICE);
        assertThat(violation.get().description()).contains("not grounded in context");
    }

    @Test
    void rejectsSubstitutedCurrencyAmountEvenIfContextHasLongerAmount() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "El saldo pendiente es de USD 100.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );

        // Context contains USD 1000, which substring 'USD 100' should NOT match
        String context = "Customer: Juan. Saldo pendiente: USD 1000.";
        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, context);
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_PRICE);
    }

    @Test
    void rejectsUnrelatedOfferTermEvenIfOtherOfferTermIsInContext() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Llévate este producto gratis hoy.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );

        // Context has 'descuento', but draft claims 'gratis'
        String context = "Customer: Juan. Notes: Aplicar descuento habitual.";
        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, context);
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_OFFER_TERM);
        assertThat(violation.get().description()).contains("'gratis'");
    }

    @Test
    void rejectsEvidenceWithoutColon() {
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Juan, gracias por tu compra.",
                DraftVariables.empty(),
                "es-419",
                List.of("Compra reciente Televisor"),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );

        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft, "Customer: Juan. Purchases: Café Tostado.");
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.UNGROUNDED_EVIDENCE);
        assertThat(violation.get().description()).contains("must be a structured citation with format 'Label: Value'");
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
        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draftUngroundedEvidence, "Customer: Juan. Compró Café.");
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.UNGROUNDED_EVIDENCE);
        assertThat(violation.get().description()).contains("factual claim not found in context");
    }

    @Test
    void rejectsUnsafeOrHallucinatedTextInWarningsOrRationale() {
        MessageDraft draftUrlInWarning = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Juan, esperamos que estés bien.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of("Warning: check https://unsafe.com"),
                "Follow-up",
                RecommendationConfidence.of(0.85)
        );
        Optional<DraftSafetyViolation> violationUrl = DraftSafetyValidator.validate(draftUrlInWarning, "Customer: Juan.");
        assertThat(violationUrl).isPresent();
        assertThat(violationUrl.get().code()).isEqualTo(DraftSafetyViolation.UNAUTHORIZED_URL);

        MessageDraft draftOfferInRationale = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Juan, esperamos que estés bien.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Se le aplica descuento acordado",
                RecommendationConfidence.of(0.85)
        );
        Optional<DraftSafetyViolation> violationOffer = DraftSafetyValidator.validate(draftOfferInRationale, "Customer: Juan.");
        assertThat(violationOffer).isPresent();
        assertThat(violationOffer.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_OFFER_TERM);
    }

    private static MessageDraft draftWith(String body, String locale, List<String> evidence) {
        return new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                body,
                DraftVariables.empty(),
                locale,
                evidence,
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.85));
    }

    @Test
    void rejectsBarePriceNumberWhenContextHasNoSuchAmount() {
        MessageDraft draft = draftWith("Hola Juan, el precio es 100.", "es-419", List.of());

        Optional<DraftSafetyViolation> violation =
                DraftSafetyValidator.validate(draft, "Customer: Juan. Preguntó por el precio.");
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_PRICE);
    }

    @Test
    void bindsEvidenceValueToTheCitedFactType() {
        MessageDraft draft = draftWith("Hola Televisor, gracias por tu compra.", "es-419",
                List.of("Compra reciente: Televisor"));
        var grounding = new DraftGroundingContext("Televisor", null, List.of("Café Molido"), List.of(), "DUE", "2026-09-29");

        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft,
                "Televisor Café Molido", grounding);
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.UNGROUNDED_EVIDENCE);

        MessageDraft grounded = draftWith("Hola, gracias por tu compra.", "es-419",
                List.of("Compra reciente: Café Molido", "Nombre: Televisor"));
        assertThat(DraftSafetyValidator.validate(grounded, "Televisor Café Molido", grounding)).isEmpty();
    }

    @Test
    void validatesNoDraftRationale() {
        NoDraft refusal = new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY,
                "Visita https://phishing.example.com para más detalles",
                RecommendationConfidence.of(0.9));

        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(refusal, "Customer: Juan.");
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.UNAUTHORIZED_URL);

        NoDraft safe = new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY, "Historial insuficiente",
                RecommendationConfidence.of(0.9));
        assertThat(DraftSafetyValidator.validate(safe, "Customer: Juan.")).isEmpty();
    }

    @Test
    void acceptsFullBcp47LocaleTags() {
        assertThat(draftWith("Hola.", "zh-Hant-TW", List.of()).locale()).isEqualTo("zh-Hant-TW");
    }

    @Test
    void barepriceIsNotGroundedByUnrelatedDatesInContext() {
        MessageDraft draft = draftWith("Hola, el precio es 2026.", "es-419", List.of());

        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft,
                "Fecha 2026-09-29. Preguntó por el precio.");
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_PRICE);
    }

    @Test
    void productLabelTakesPrecedenceOverCustomerNameLabel() {
        MessageDraft draft = draftWith("Hola.", "es-419", List.of("Nombre del producto: Televisor"));
        var grounding = new DraftGroundingContext("Televisor", null, List.of("Café Molido"), List.of(), "DUE", "2026-09-29");

        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft,
                "Televisor Café Molido", grounding);
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.UNGROUNDED_EVIDENCE);
    }

    @Test
    void rejectsGroupedAmountThatOnlySharesAPrefixWithContext() {
        MessageDraft draft = draftWith("Hola, te ofrecemos USD 1,000,000.", "es-419", List.of());

        Optional<DraftSafetyViolation> violation =
                DraftSafetyValidator.validate(draft, "Cliente pagó USD 1,000 antes.");
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_PRICE);
    }

    @Test
    void offerTermInIdentityFieldDoesNotAuthorizeAnOffer() {
        MessageDraft draft = draftWith("Hola, el producto es gratis.", "es-419", List.of());
        var grounding = new DraftGroundingContext("Gratis", "Cliente frecuente", List.of("Café"),
                List.of(), "DUE", "2026-09-29");

        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft,
                "Gratis Cliente frecuente Café", grounding);
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_OFFER_TERM);
    }

    @Test
    void rejectsUnknownEvidenceLabelsAndAcceptsFollowUpStatus() {
        var grounding = new DraftGroundingContext("Televisor", null, List.of("Café Molido"),
                List.of(), "OVERDUE", "2026-09-29");

        MessageDraft unknown = draftWith("Hola.", "es-419", List.of("Pedido reciente: Televisor"));
        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(unknown,
                "Televisor Café Molido OVERDUE", grounding);
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.UNGROUNDED_EVIDENCE);

        MessageDraft status = draftWith("Hola.", "es-419", List.of("Estado de seguimiento: OVERDUE"));
        assertThat(DraftSafetyValidator.validate(status, "Televisor Café Molido OVERDUE", grounding)).isEmpty();
    }

    @Test
    void acceptsLongCanonicalBcp47Tags() {
        assertThat(draftWith("Hola.", "sl-rozaj-biske-1994", List.of()).locale()).isEqualTo("sl-rozaj-biske-1994");
    }

    @Test
    void rejectsInternationalizedBareDomainsButNotPlainProse() {
        assertThat(DraftSafetyValidator.containsUrl("Visita café.cr hoy")).isTrue();
        assertThat(DraftSafetyValidator.containsUrl("Visita tienda.みんな hoy")).isTrue();
        assertThat(DraftSafetyValidator.containsUrl("Hola. Gracias por tu compra.")).isFalse();
    }

    @Test
    void rejectsExpandedPromotionForms() {
        Optional<DraftSafetyViolation> twoForOne = DraftSafetyValidator.validate(
                draftWith("Tenemos 2x1 para ti.", "es-419", List.of()), "Customer: Juan.");
        assertThat(twoForOne).isPresent();
        assertThat(twoForOne.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_OFFER_TERM);

        assertThat(DraftSafetyValidator.validate(
                draftWith("Envío gratuito en tu pedido.", "es-419", List.of()), "Customer: Juan.")).isPresent();
    }

    @Test
    void refusalOfferIsGroundedOnlyInOfferBearingFields() {
        var grounding = new DraftGroundingContext("Gratis", null, List.of("Café"), List.of(), "DUE", "2026-09-29");
        NoDraft refusal = new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY, "Todo es gratis para este cliente",
                RecommendationConfidence.of(0.9));

        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(refusal, "Gratis Café", grounding);
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_OFFER_TERM);
    }

    @Test
    void refusalMayStateThatThereIsNoOfferButNeverAdvertiseOne() {
        for (String benign : List.of("No hay una oferta relevante para este cliente",
                "Sin descuentos ni promociones vigentes", "No existe ninguna oferta aplicable",
                "Sin descuentos ni promociones vigentes", "No hay descuento, ni oferta", "Sin descuentos o promociones vigentes",
                "No hay cashback disponible", "No existe ninguna liquidación vigente", "No hay opciones sin costo",
                "No hay un precio disponible", "No aplica Black Friday", "No hay ningún 2 por 1 disponible")) {
            assertThat(DraftSafetyValidator.validate(
                    new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY, benign, RecommendationConfidence.of(0.9)),
                    "Customer: Juan.")).as(benign).isEmpty();
        }
        for (String unsafe : List.of("No hay una oferta relevante, pero tenemos un descuento especial",
                "No hay oferta; aprovecha el 20% hoy", "No hay oferta de $50, llama ya", "Hay una oferta relevante",
                "No hay descuento, oferta vigente para ti", "No hay descuento y oferta vigente", "No hay cashback, pero tienes liquidación", "Todo es gratis para este cliente",
                "No hay precio, cuesta 50", "Black Friday hoy", "No hay precio, pero USD 20")) {
            assertThat(DraftSafetyValidator.validate(
                    new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY, unsafe, RecommendationConfidence.of(0.9)),
                    "Customer: Juan.")).as(unsafe).isPresent();
        }
        assertThat(DraftSafetyValidator.validate(
                draftWith("No hay una oferta relevante.", "es-419", List.of()), "Customer: Juan.")).isPresent();
    }

    @Test
    void acceptsVeryLongCanonicalBcp47Tags() {
        String tag = "en-Latn-US-u-ca-gregory-nu-latn-x-foo";
        assertThat(draftWith("Hola.", tag, List.of()).locale()).isEqualTo(tag);
    }

    @Test
    void rejectsSpaceGroupedAmountsThatShareAPrefix() {
        assertThat(DraftSafetyValidator.validate(
                draftWith("Te damos ₡10 000 hoy.", "es-419", List.of()), "Pagó ₡10 antes.")).isPresent();
        assertThat(DraftSafetyValidator.validate(
                draftWith("Te damos USD 1\u00a0000 hoy.", "es-419", List.of()), "Pagó USD 1 antes.")).isPresent();
    }

    @Test
    void recognizesAdditionalCurrencies() {
        assertThat(DraftSafetyValidator.validate(
                draftWith("Aprovecha 100 pesos de crédito.", "es-419", List.of()), "Customer: Juan.")).isPresent();
        assertThat(DraftSafetyValidator.validate(
                draftWith("Te damos MXN 100 hoy.", "es-419", List.of()), "Customer: Juan.")).isPresent();
    }

    @Test
    void scalarEvidenceRequiresExactOrWholeWordMatch() {
        var grounding = new DraftGroundingContext("Mariana Pérez", null, List.of("Café"),
                List.of("2026-08-01"), "DUE", "2026-09-29");

        assertThat(DraftSafetyValidator.validate(
                draftWith("Hola.", "es-419", List.of("Nombre: Ana")), "Mariana Pérez", grounding)).isPresent();
        assertThat(DraftSafetyValidator.validate(
                draftWith("Hola.", "es-419", List.of("Nombre: Mariana")), "Mariana Pérez", grounding)).isEmpty();
        assertThat(DraftSafetyValidator.validate(
                draftWith("Hola.", "es-419", List.of("Fecha de compra: 2026-08")), "2026-08-01", grounding)).isPresent();
        assertThat(DraftSafetyValidator.validate(
                draftWith("Hola.", "es-419", List.of("Fecha de compra: 2026-08-01")), "2026-08-01", grounding)).isEmpty();
    }

    @Test
    void rejectsNonHierarchicalUriSchemesButNotEvidenceLabels() {
        assertThat(DraftSafetyValidator.containsUrl("Escribe a mailto:ventas@localhost")).isTrue();
        assertThat(DraftSafetyValidator.containsUrl("Llama tel:+50655551234")).isTrue();
        assertThat(DraftSafetyValidator.containsUrl("Envía sms:+50655551234")).isTrue();
        assertThat(DraftSafetyValidator.containsUrl("Compra: Café Molido")).isFalse();
        assertThat(DraftSafetyValidator.containsUrl("Nota: hola")).isFalse();
    }

    @Test
    void currencyWordBeforeNumberRequiresGroundedAmount() {
        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(
                draftWith("Obtén pesos 100 de crédito.", "es-419", List.of()), "Aceptamos pesos.");
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_PRICE);
    }

    @Test
    void monetaryBaselineComesFromPurchaseDescriptionsOnly() {
        var grounding = new DraftGroundingContext("USD 100", "Aceptamos USD", List.of("Café"),
                List.of(), "DUE", "2026-09-29");
        MessageDraft draft = draftWith("Crédito de USD 100 para ti.", "es-419", List.of());

        Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(draft,
                "USD 100 Aceptamos USD Café", grounding);
        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_PRICE);

        // Customer notes are untrusted and never authorize a price; purchase descriptions do.
        var withPriceInNotes = new DraftGroundingContext("Juan", "Ofrecimos USD 100 antes", List.of("Café"),
                List.of(), "DUE", "2026-09-29");
        assertThat(DraftSafetyValidator.validate(draft, "Juan Ofrecimos USD 100 antes Café", withPriceInNotes))
                .isPresent();
        var withPriceInPurchase = new DraftGroundingContext("Juan", null, List.of("Plan USD 100"),
                List.of(), "DUE", "2026-09-29");
        assertThat(DraftSafetyValidator.validate(draft, "Juan Plan USD 100", withPriceInPurchase)).isEmpty();
    }

    @Test
    void unicodeSpacesDoNotEvadeAmountOrPercentageExtraction() {
        Optional<DraftSafetyViolation> amount = DraftSafetyValidator.validate(
                draftWith("Crédito de USD\u00a0100 para ti.", "es-419", List.of()), "Aceptamos USD");
        assertThat(amount).isPresent();
        assertThat(amount.get().code()).isEqualTo(DraftSafetyViolation.HALLUCINATED_PRICE);

        Optional<DraftSafetyViolation> percentage = DraftSafetyValidator.validate(
                draftWith("Tienes 50\u202f% hoy.", "es-419", List.of()), "Customer: Juan.");
        assertThat(percentage).isPresent();

        assertThat(DraftSafetyValidator.validate(
                draftWith("Crédito de USD\u00a0100 para ti.", "es-419", List.of()), "Ofrecimos USD 100 antes"))
                .isEmpty();
    }

    private static final String UNGROUNDED_CONTEXT = "Cliente: Juan. Compras: Café molido 250 g.";

    @Test
    void rejectsUnicodeAndNotationVariantsOfUngroundedClaims() {
        record Probe(String body, String code) { }
        List<Probe> probes = List.of(
                new Probe("Hoy por \uff04\uff11\uff10.", DraftSafetyViolation.HALLUCINATED_PRICE),
                new Probe("Con \uff11\uff10% menos.", DraftSafetyViolation.HALLUCINATED_PERCENTAGE),
                new Probe("Con 10\uff05 menos.", DraftSafetyViolation.HALLUCINATED_PERCENTAGE),
                new Probe("Con 10 por ciento menos.", DraftSafetyViolation.HALLUCINATED_PERCENTAGE),
                new Probe("Con 10 pct menos.", DraftSafetyViolation.HALLUCINATED_PERCENTAGE),
                new Probe("Hoy por \u00a51000.", DraftSafetyViolation.HALLUCINATED_PRICE),
                new Probe("Hoy por \uffe51000.", DraftSafetyViolation.HALLUCINATED_PRICE),
                new Probe("Hoy por 1000 \u20a9.", DraftSafetyViolation.HALLUCINATED_PRICE),
                new Probe("Con des\u200bcuento.", DraftSafetyViolation.HALLUCINATED_OFFER_TERM),
                new Probe("Es gra\u200btis.", DraftSafetyViolation.HALLUCINATED_OFFER_TERM),
                new Probe("Es gra\u00adtis.", DraftSafetyViolation.HALLUCINATED_OFFER_TERM),
                new Probe("En \u043eferta.", DraftSafetyViolation.HALLUCINATED_OFFER_TERM),
                new Probe("Con d3scuento.", DraftSafetyViolation.HALLUCINATED_OFFER_TERM),
                new Probe("Es gr4tis.", DraftSafetyViolation.HALLUCINATED_OFFER_TERM),
                new Probe("En promo[.]test.", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("En promo(dot)test.", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("En hxxps[://]promo.test/x.", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("En www\u3002promo\u3002test.", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("En promo\u3002test/x.", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("Visita promo\u3002dev", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("Visita promo\uff61dev ahora.", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("Visita promo\u3002DEV", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("Visita promo [.] test", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("Visita promo (dot) test", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("Visita hxxps [://] promo.test", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("Visita 10 pct.com", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("Visita 10 porciento.com/x", DraftSafetyViolation.UNAUTHORIZED_URL),
                new Probe("Con des\ufe0fcuento.", DraftSafetyViolation.HALLUCINATED_OFFER_TERM),
                new Probe("Con des\udb40\udd00cuento.", DraftSafetyViolation.HALLUCINATED_OFFER_TERM),
                new Probe("Es gra\u034ftis.", DraftSafetyViolation.HALLUCINATED_OFFER_TERM),
                new Probe("Es gra\u180btis.", DraftSafetyViolation.HALLUCINATED_OFFER_TERM),
                new Probe("Visita promo\u3002Dev", DraftSafetyViolation.UNAUTHORIZED_URL));
        for (Probe probe : probes) {
            Optional<DraftSafetyViolation> violation = DraftSafetyValidator.validate(
                    draftWith(probe.body(), "es-419", List.of()), UNGROUNDED_CONTEXT);
            assertThat(violation).as(probe.body()).isPresent();
            assertThat(violation.get().code()).as(probe.body()).isEqualTo(probe.code());
        }
    }

    @Test
    void variantSpellingsStillMatchGroundedClaims() {
        assertThat(DraftSafetyValidator.validate(
                draftWith("Con \uff11\uff10\uff05 menos.", "es-419", List.of()), "Se acordó 10% de descuento.")).isEmpty();
        assertThat(DraftSafetyValidator.validate(
                draftWith("Con 10 por ciento de des\u200bcuento.", "es-419", List.of()), "Se acordó 10% de descuento."))
                .isEmpty();
        assertThat(DraftSafetyValidator.validate(
                draftWith("Plan por \u00a51000.", "es-419", List.of()), "Plan de \u00a51000")).isEmpty();
    }

    @Test
    void ordinaryTextIsNotFlaggedByNormalization() {
        for (String benign : List.of("\u00bfc\u00f3mo te fue con tu Caf\u00e9 molido 250 g?", "Tu pedido de 3 cajas de caf\u00e9 en la semana 4.",
                "Hola \u041c\u0430\u0440\u0438\u044f, gracias por tu compra.", "Visita pronto, \u03b1 \u03b2 \u03b3.")) {
            assertThat(DraftSafetyValidator.validate(draftWith(benign, "es-419", List.of()), UNGROUNDED_CONTEXT))
                    .as(benign).isEmpty();
        }
    }

    @Test
    void leetSpellingInGroundedContextAndIdeographicStopsAreNotFalsePositives() {
        assertThat(DraftSafetyValidator.validate(
                draftWith("Aprovecha d3scuento.", "es-419", List.of()), "Plan con d3scuento")).isEmpty();
        assertThat(DraftSafetyValidator.validate(
                draftWith("Aprovecha d3scuento.", "es-419", List.of()), "Plan sin nada")).isPresent();
        // Stops with a space or at the end of a sentence are punctuation; between letters they separate a host.
        for (String prose : List.of("Hola\u3002 Gracias por tu compra", "Gracias por tu compra\u3002")) {
            assertThat(DraftSafetyValidator.validate(draftWith(prose, "es-419", List.of()), "Cliente: Juan."))
                    .as(prose).isEmpty();
        }
        assertThat(DraftSafetyValidator.validate(draftWith("Hola\u3002Gracias por tu compra", "es-419", List.of()),
                "Cliente: Juan.")).isPresent();
    }

    @Test
    void compactCurrencyAmountsAndFoldedCurrencyCodesNeedGroundedAmounts() {
        for (String body : List.of("Paga JPY100.", "Paga USD100.", "Paga U5D 100.", "Paga 100USD.")) {
            assertThat(DraftSafetyValidator.validate(draftWith(body, "es-419", List.of()), "Plan USD 50"))
                    .as(body).isPresent();
        }
        assertThat(DraftSafetyValidator.validate(draftWith("Paga JPY100.", "es-419", List.of()), "Plan JPY100")).isEmpty();
        assertThat(DraftSafetyValidator.validate(draftWith("Paga USD100.", "es-419", List.of()), "Plan USD 100")).isEmpty();
        assertThat(DraftSafetyValidator.validate(draftWith("Paga U5D 100.", "es-419", List.of()), "Plan USD 100")).isEmpty();
    }

    @Test
    void compactFoldedAmountsDefangedDomainsAfterPercentagesAndLongerPromotionsAreNotGrounded() {
        for (String body : List.of("Paga U5D100.", "Paga u5d100 hoy.")) {
            assertThat(DraftSafetyValidator.validate(draftWith(body, "es-419", List.of()), "Plan USD 50"))
                    .as(body).isPresent();
        }
        assertThat(DraftSafetyValidator.validate(draftWith("Paga U5D100.", "es-419", List.of()), "Plan USD 100")).isEmpty();
        for (String body : List.of("Visita 10 pct[.]com", "Visita 10 pct (dot) com", "Visita 10 pct\u3002com")) {
            DraftSafetyViolation violation = DraftSafetyValidator.validate(
                    draftWith(body, "es-419", List.of()), "Se acord\u00f3 10% de descuento").orElseThrow();
            assertThat(violation.code()).as(body).isEqualTo(DraftSafetyViolation.UNAUTHORIZED_URL);
        }
        assertThat(DraftSafetyValidator.validate(
                draftWith("Aprovecha 2x1.", "es-419", List.of()), "Plan 2x10")).isPresent();
        assertThat(DraftSafetyValidator.validate(
                draftWith("Aprovecha 2x10.", "es-419", List.of()), "Plan 2x10")).isEmpty();
        assertThat(DraftSafetyValidator.validate(
                draftWith("Es un b0n0.", "es-419", List.of()), "Incluye b0n0")).isEmpty();
    }

    @Test
    void leadingNumericAmountsAndSeparatedCombiningMarksAreStillChecked() {
        assertThat(DraftSafetyValidator.validate(draftWith("Paga 100U5D.", "es-419", List.of()), "Plan USD 50")).isPresent();
        assertThat(DraftSafetyValidator.validate(draftWith("Paga 100U5D.", "es-419", List.of()), "Plan 100 USD")).isEmpty();
        assertThat(DraftSafetyValidator.validate(draftWith("Tienes un cupo\u200b\u0301n.", "es-419", List.of()),
                "Customer: Juan.")).isPresent();
        assertThat(DraftSafetyValidator.validate(draftWith("Tienes un cupo\u200b\u0301n.", "es-419", List.of()),
                "Incluye un cup\u00f3n")).isEmpty();
    }

    @Test
    void typedEvidenceSourcesMatchUnderTheSameCanonicalizationAsTheRestOfTheDraft() {
        var grounding = new DraftGroundingContext("Ana", null, List.of("Plan de $10"), List.of(), "DUE", "2026-09-29");
        assertThat(DraftSafetyValidator.validate(
                draftWith("Plan por $10.", "es-419", List.of("Compra: Plan de \uff04\uff11\uff10")),
                "Plan de $10", grounding)).isEmpty();
        assertThat(DraftSafetyValidator.validate(
                draftWith("Hola.", "es-419", List.of("Compra: Plan de \uff04\uff12\uff10")),
                "Plan de $10", grounding)).isPresent();
    }

    @Test
    void compactPromotionStaysVisibleInLeetRefusalChains() {
        assertThat(DraftSafetyValidator.validate(new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY,
                "No hay 2x1 ni d3scuento disponible", RecommendationConfidence.of(0.9)), "Customer: Juan.")).isEmpty();
        assertThat(DraftSafetyValidator.validate(new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY,
                "No hay 2x1, pero tienes d3scuento", RecommendationConfidence.of(0.9)), "Customer: Juan.")).isPresent();
    }

    @Test
    void leetSpellingInRefusalNegationAndSpacedPercentMarkersAreHandledSymmetrically() {
        assertThat(DraftSafetyValidator.validate(new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY,
                "No hay d3scuento disponible", RecommendationConfidence.of(0.9)), "Customer: Juan.")).isEmpty();
        assertThat(DraftSafetyValidator.validate(new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY,
                "No hay d3scuento, pero tienes d3scuento", RecommendationConfidence.of(0.9)), "Customer: Juan."))
                .isPresent();
        for (String[] pair : new String[][] {{"Con 10 por ciento menos.", "Se acord\u00f3 10 % de descuento."},
                {"Con 10 % menos.", "Se acord\u00f3 10 por ciento de descuento."},
                {"Con 10% menos.", "Se acord\u00f3 10 % de descuento."},
                {"Plan por $ 100.", "Plan de $100"}, {"Plan por USD100.", "Plan de USD 100"}}) {
            assertThat(DraftSafetyValidator.validate(draftWith(pair[0], "es-419", List.of()), pair[1]))
                    .as(pair[0] + " | " + pair[1]).isEmpty();
        }
        assertThat(DraftSafetyValidator.validate(
                draftWith("Con 10 por ciento menos.", "es-419", List.of()), "Se acord\u00f3 15 % de descuento.")).isPresent();
    }

    @Test
    void refusalNegationIsStillNeutralisedAfterNormalization() {
        assertThat(DraftSafetyValidator.validate(new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY,
                "No hay una of\u200berta relevante", RecommendationConfidence.of(0.9)), "Customer: Juan.")).isEmpty();
        assertThat(DraftSafetyValidator.validate(new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY,
                "No hay una oferta, pero tienes 10\uff05", RecommendationConfidence.of(0.9)), "Customer: Juan.")).isPresent();
    }
}
