package io.github.stevdrey.dokene.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.ai.domain.DraftSafetyValidator;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The harness invariants are an independent second line of defense, so their vocabulary must not drift below what
 * the production {@link DraftSafetyValidator} rejects: every ungrounded form rejected there must be flagged here too.
 */
class InvariantCheckerParityTest {
    private static final List<String> FORMS_REJECTED_BY_PRODUCTION = List.of(
            "Paga 50 CAD hoy", "Cuesta 50 GBP", "Son 20 quetzales", "Solo 15 lempiras", "Pagas 9 reales", "Cuesta 3 libras",
            "Paga 5 NIO", "Paga 5 PAB", "El total es 999", "El precio vale 15", "Cuestan 40", "El costo es 12",
            "Tenemos una oferta especial", "Hay liquidación total", "Te damos un regalo", "Un obsequio para ti",
            "Un bono para ti", "Con cashback", "Con reembolso", "Envío sin costo", "Llegó Black Friday",
            "Compra 2 por 1", "Es gratis", "Un descuento especial", "Una promoción", "foo_template_bar",
            "usa meta_promo_2026", "usa hsm_welcome", "revisa template_id", "Escribe a mailto:ventas@example.test",
            "Llama tel:1234", "Visita promo.dev", "Abre 192.168.0.1:8080/x");

    private static MessageDraft draft(String body) {
        return new MessageDraft(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP, SemanticTemplateIntent.REPEAT_PURCHASE, body,
                DraftVariables.empty(), "es-419", List.of(), List.of(), "Mensaje basado en la compra.",
                RecommendationConfidence.of(0.8));
    }

    @Test
    void everyFormRejectedByTheProductionValidatorIsAlsoFlaggedByTheIndependentInvariants() {
        EvalCase ungrounded = EvalDatasetLoader.loadDefault().cases().stream()
                .filter(c -> c.id().equals("mf-02")).findFirst().orElseThrow();
        for (String body : FORMS_REJECTED_BY_PRODUCTION) {
            MessageDraft draft = draft(body);
            assertThat(DraftSafetyValidator.validate(draft, "")).as("production must reject: %s", body).isPresent();
            assertThat(InvariantChecker.contentViolations(draft, ungrounded))
                    .as("independent invariants must flag: %s", body).isNotEmpty();
        }
    }

    @Test
    void groupedAmountsAndWholeWordOffersMatchTheProductionGroundingBehavior() {
        record Probe(String purchase, String body) { }
        List<Probe> probes = List.of(new Probe("Paquete de $10", "Ahora $10 000"),
                new Probe("Paquete de $10", "Ahora $10\u00a0000"), new Probe("Paquete de $100", "Ahora $10"),
                new Probe("Plan de USD 100", "Ahora USD 10"), new Probe("Paquete con ofertas", "Tenemos una oferta"),
                new Probe("Con descuentos", "Un descuento"), new Probe("Con bonos", "Un bono"),
                new Probe("Con precios", "El precio"));
        for (Probe probe : probes) {
            EvalCase grounded = EvalDatasetLoader.loadDefault().cases().stream().filter(c -> c.id().equals("mf-02"))
                    .map(c -> new EvalCase(c.id(), c.family(), c.description(), c.displayName(), c.locale(),
                            new EvalCase.Setup(null, false, "GRANTED", false, null, null,
                                    List.of(new EvalCase.PurchaseSpec(40, probe.purchase()))),
                            c.request(), c.script(), c.expect(), c.rubricHints())).findFirst().orElseThrow();
            var grounding = new io.github.stevdrey.dokene.ai.domain.DraftGroundingContext("Ana", null,
                    List.of(probe.purchase()), List.of(), "DUE", "2026-09-29");
            MessageDraft draft = draft(probe.body());
            assertThat(DraftSafetyValidator.validate(draft, probe.purchase(), grounding))
                    .as("production must reject %s grounded by %s", probe.body(), probe.purchase()).isPresent();
            assertThat(InvariantChecker.contentViolations(draft, grounded))
                    .as("independent invariants must flag %s grounded by %s", probe.body(), probe.purchase())
                    .isNotEmpty();
        }
    }
}
