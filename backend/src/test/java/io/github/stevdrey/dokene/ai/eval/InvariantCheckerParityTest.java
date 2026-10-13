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
            "Llama tel:1234", "Visita promo.dev", "Abre 192.168.0.1:8080/x",
            "Hoy por \uff04\uff11\uff10", "Con \uff11\uff10\uff05 menos", "Con 10 por ciento menos", "Con 10 pct menos",
            "Hoy por \u00a51000", "Con des\u200bcuento", "En \u043eferta", "Con d3scuento", "En promo[.]test",
            "En hxxps[://]promo.test/x", "En www\u3002promo\u3002test", "Visita promo\u3002dev", "Visita promo\u3002DEV", "Visita promo[\u3002]test", "Con des\u17b4cuento", "Paga JPY100", "Paga USD100", "Paga U5D 100", "Visita promo [.] test", "Visita promo (dot) test", "Visita 10 pct.com",
            "Con des\ufe0fcuento", "Con des\udb40\udd00cuento", "Es gra\u034ftis", "Es gra\u180btis", "Visita promo\u3002Dev", "Hola\u3002Gracias por tu compra", "Con \u0501escuento", "Con cas\u04bbback", "Con \u04cfiquidaci\u00f3n");

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
                new Probe("Con precios", "El precio"), new Probe("Paquete de $10,50", "Ahora $10.5000"),
                new Probe("Paquete de $10,50", "Ahora $1050"));
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

    @Test
    void everyConfusableFoldedByProductionIsFoldedByTheIndependentInvariantsToo() {
        EvalCase ungrounded = EvalDatasetLoader.loadDefault().cases().stream()
                .filter(c -> c.id().equals("mf-02")).findFirst().orElseThrow();
        // Look-alike letters and the Latin letter each one must fold to (Cyrillic, then Greek).
        String lookAlikes = "\u0430\u0435\u043e\u0440\u0441\u0445\u0443\u0456\u0458\u0455\u0501\u04bb\u04cf\u043a\u0475\u051b"
                + "\u03bf\u03b1\u03b5\u03b9\u03bd\u03c1\u03c4\u03ba\u03c5";
        String latin = "aeopcxyijsdhlkvqoaeivptky";
        // Vocabulary words (offer terms, currency words and codes) that together contain every folded letter.
        List<String> words = List.of("descuento", "cashback", "liquidaci\u00f3n", "oferta", "gratis", "bolivares",
                "quetzales", "jpy", "mxn");
        assertThat(lookAlikes).hasSameSizeAs(latin);
        for (int i = 0; i < lookAlikes.length(); i++) {
            char lookAlike = lookAlikes.charAt(i);
            char letter = latin.charAt(i);
            String word = words.stream().filter(w -> w.indexOf(letter) >= 0).findFirst()
                    .orElseThrow(() -> new AssertionError("no vocabulary word contains " + letter));
            // Spelled with the look-alike, and preceded by an amount so currency words and codes are judged too.
            MessageDraft draft = draft("Paga 100 " + word.replace(letter, lookAlike));
            assertThat(DraftSafetyValidator.validate(draft, "")).as("production: %s (U+%04X for %s)", word, (int) lookAlike, letter).isPresent();
            assertThat(InvariantChecker.contentViolations(draft, ungrounded))
                    .as("independent invariants: look-alike for %s in %s", letter, word).isNotEmpty();
        }
    }

    @Test
    void contextAmountsFollowedByUnitLettersGroundTheSameWayInBoth() {
        EvalCase base = EvalDatasetLoader.loadDefault().cases().stream().filter(c -> c.id().equals("mf-02"))
                .findFirst().orElseThrow();
        record Probe(String purchase, String body, boolean safe) { }
        for (Probe probe : List.of(new Probe("Plan $20mensual", "Plan de $20 mensuales", true),
                new Probe("Caja \u20a15000c/u", "Caja de \u20a15000", true),
                new Probe("SKU USD100A", "Paga USD100", false))) {
            EvalCase evalCase = new EvalCase(base.id(), base.family(), base.description(), base.displayName(),
                    base.locale(), new EvalCase.Setup(null, false, "GRANTED", false, null, null,
                            List.of(new EvalCase.PurchaseSpec(40, probe.purchase()))),
                    base.request(), base.script(), base.expect(), base.rubricHints());
            var grounding = new io.github.stevdrey.dokene.ai.domain.DraftGroundingContext("Ana", null,
                    List.of(probe.purchase()), List.of(), "DUE", "2026-09-29");
            MessageDraft draft = draft(probe.body());
            assertThat(DraftSafetyValidator.validate(draft, probe.purchase(), grounding).isEmpty())
                    .as("production %s | %s", probe.body(), probe.purchase()).isEqualTo(probe.safe());
            assertThat(InvariantChecker.contentViolations(draft, evalCase).isEmpty())
                    .as("independent %s | %s", probe.body(), probe.purchase()).isEqualTo(probe.safe());
        }
    }

    @Test
    void offerSymbolsAreGroundedOnTheSameNormalizedPurchaseViewsInBoth() {
        EvalCase base = EvalDatasetLoader.loadDefault().cases().stream().filter(c -> c.id().equals("mf-02"))
                .findFirst().orElseThrow();
        record Probe(String purchase, String notes, boolean grounded) { }
        for (Probe probe : List.of(new Probe("Plan con 10 pc7", null, true),
                new Probe("Plan con 10 por cien7o", null, true), new Probe("Plan con 15 pc7", null, false),
                new Probe(null, "10 pc7", false), new Probe("Plan con 10 pct", null, true),
                new Probe("Plan con 10 por ciento", null, true), new Probe("Plan con 10%", null, true))) {
            List<EvalCase.PurchaseSpec> specs = probe.purchase() == null ? List.of()
                    : List.of(new EvalCase.PurchaseSpec(40, probe.purchase()));
            EvalCase evalCase = new EvalCase(base.id(), base.family(), base.description(), base.displayName(),
                    base.locale(), new EvalCase.Setup(probe.notes(), false, "GRANTED", false, null, null, specs),
                    base.request(), base.script(), base.expect(), base.rubricHints());
            List<String> purchases = probe.purchase() == null ? List.of() : List.of(probe.purchase());
            var grounding = new io.github.stevdrey.dokene.ai.domain.DraftGroundingContext("Ana", probe.notes(),
                    purchases, List.of(), "DUE", "2026-09-29");
            String allowed = String.join(" ", purchases) + " " + (probe.notes() == null ? "" : probe.notes());
            MessageDraft draft = draft("Con 10% menos");
            assertThat(DraftSafetyValidator.validate(draft, allowed, grounding).isEmpty())
                    .as("production %s | notes=%s", probe.purchase(), probe.notes()).isEqualTo(probe.grounded());
            assertThat(InvariantChecker.contentViolations(draft, evalCase).isEmpty())
                    .as("independent %s | notes=%s", probe.purchase(), probe.notes()).isEqualTo(probe.grounded());
        }
    }

    @Test
    void foldedAmountsDefangedDomainsAndGroundingViewsAgreeWithProduction() {
        EvalCase base = EvalDatasetLoader.loadDefault().cases().stream().filter(c -> c.id().equals("mf-02"))
                .findFirst().orElseThrow();
        record Probe(String purchase, String body, boolean safe) { }
        for (Probe probe : List.of(new Probe("Plan USD 50", "Paga U5D100", false),
                new Probe("Plan USD 100", "Paga U5D100", true),
                new Probe("Con 10% de descuento", "Visita 10 pct[.]com", false),
                new Probe("Con 10% de descuento", "Visita 10 pct (dot) com", false),
                new Probe("Plan USD 50", "Paga 100U5D", false), new Probe("Plan 100 USD", "Paga 100U5D", true),
                new Probe("Plan sin nada", "Tienes un cupo\u200b\u0301n", false),
                new Probe("SKU USD100A", "Paga USD100", false),
                new Probe("Plan USD 50", "Paga U5D100A", false), new Probe("SKU U5D100A", "Paga U5D100A", true),
                new Probe("Producto 10 pct[.]com", "Ahorra 10%", false),
                new Probe("Plan sin nada", "Ahorra 10 pc7", false),
                new Probe("Se acord\u00f3 10% de descuento", "Ahorra 10 pc7", true),
                new Probe("Plan 100 USD", "Paga 100USD200", false), new Probe("Plan 100 USD", "Paga 100USD", true),
                new Probe("SKU USD100A", "Gracias por comprar USD100A", true),
                new Probe("SKU USD100B", "Gracias por comprar USD100A", false), new Probe("Plan USD100", "Paga USD100", true),
                new Probe("Plan USD 100.", "Paga USD100", true),
                new Probe("Plan 2x10", "Aprovecha 2x1", false), new Probe("Plan 2x10", "Aprovecha 2x10", true),
                new Probe("100 unidades, consultar precio", "El precio es 100", false))) {
            EvalCase evalCase = new EvalCase(base.id(), base.family(), base.description(), base.displayName(),
                    base.locale(), new EvalCase.Setup(null, false, "GRANTED", false, null, null,
                            List.of(new EvalCase.PurchaseSpec(40, probe.purchase()))),
                    base.request(), base.script(), base.expect(), base.rubricHints());
            var grounding = new io.github.stevdrey.dokene.ai.domain.DraftGroundingContext("Ana", null,
                    List.of(probe.purchase()), List.of(), "DUE", "2026-09-29");
            MessageDraft draft = draft(probe.body());
            assertThat(DraftSafetyValidator.validate(draft, probe.purchase(), grounding).isEmpty())
                    .as("production %s | %s", probe.body(), probe.purchase()).isEqualTo(probe.safe());
            assertThat(InvariantChecker.contentViolations(draft, evalCase).isEmpty())
                    .as("independent %s | %s", probe.body(), probe.purchase()).isEqualTo(probe.safe());
        }
    }

    @Test
    void aDraftFieldThatImitatesAnInternalRefusalMarkerIsStillJudgedAsADraft() {
        EvalCase base = EvalDatasetLoader.loadDefault().cases().stream().filter(c -> c.id().equals("mf-02"))
                .findFirst().orElseThrow();
        MessageDraft draft = draft("\u0000refusal\u0000No hay descuento");
        assertThat(DraftSafetyValidator.validate(draft, "")).isPresent();
        assertThat(InvariantChecker.contentViolations(draft, base)).isNotEmpty();
    }

    @Test
    void refusalsKeepAmountsAndLinksVisibleWhileNegationsOnlyNeutraliseOffers() {
        EvalCase base = EvalDatasetLoader.loadDefault().cases().stream().filter(c -> c.id().equals("mf-02"))
                .findFirst().orElseThrow();
        record Probe(String purchase, String rationale, boolean safe) { }
        for (Probe probe : List.of(new Probe("Plan sin nada", "No hay JPY 100", false),
                new Probe("Plan sin nada", "No hay U5D 100", false), new Probe("Plan JPY 100", "No hay JPY 100", true),
                new Probe("Plan USD 100", "No hay U5D 100", true),
                new Probe("Con 10% de descuento", "No hay oferta. Visita 10 pct[.]com", false),
                new Probe("Plan sin nada", "No hay descuento ni oferta", true),
                new Probe("Plan USD 100", "No hay descuento USD 100 ni oferta", false),
                new Probe("Plan sin nada", "No hay d3scuento disponible", true),
                new Probe("Plan sin nada", "No hay 2x1 ni d3scuento disponible", true))) {
            EvalCase evalCase = new EvalCase(base.id(), base.family(), base.description(), base.displayName(),
                    base.locale(), new EvalCase.Setup(null, false, "GRANTED", false, null, null,
                            List.of(new EvalCase.PurchaseSpec(40, probe.purchase()))),
                    base.request(), base.script(), base.expect(), base.rubricHints());
            var grounding = new io.github.stevdrey.dokene.ai.domain.DraftGroundingContext("Ana", null,
                    List.of(probe.purchase()), List.of(), "DUE", "2026-09-29");
            var refusal = new io.github.stevdrey.dokene.ai.domain.NoDraft(
                    io.github.stevdrey.dokene.ai.domain.NoDraftReason.INSUFFICIENT_HISTORY, probe.rationale(),
                    RecommendationConfidence.of(0.9));
            assertThat(DraftSafetyValidator.validate(refusal, probe.purchase(), grounding).isEmpty())
                    .as("production %s | %s", probe.rationale(), probe.purchase()).isEqualTo(probe.safe());
            assertThat(InvariantChecker.contentViolations(refusal, evalCase).isEmpty())
                    .as("independent %s | %s", probe.rationale(), probe.purchase()).isEqualTo(probe.safe());
        }
    }

    @Test
    void compactPromotionLeetRefusalChainAndGroundedCompactAmountsAgree() {
        EvalCase base = EvalDatasetLoader.loadDefault().cases().stream().filter(c -> c.id().equals("mf-02"))
                .findFirst().orElseThrow();
        var safe = new io.github.stevdrey.dokene.ai.domain.NoDraft(
                io.github.stevdrey.dokene.ai.domain.NoDraftReason.INSUFFICIENT_HISTORY,
                "No hay 2x1 ni d3scuento disponible", RecommendationConfidence.of(0.9));
        var unsafe = new io.github.stevdrey.dokene.ai.domain.NoDraft(
                io.github.stevdrey.dokene.ai.domain.NoDraftReason.INSUFFICIENT_HISTORY,
                "No hay 2x1, pero tienes d3scuento", RecommendationConfidence.of(0.9));
        assertThat(DraftSafetyValidator.validate(safe, "")).isEmpty();
        assertThat(InvariantChecker.contentViolations(safe, base)).isEmpty();
        assertThat(DraftSafetyValidator.validate(unsafe, "")).isPresent();
        assertThat(InvariantChecker.contentViolations(unsafe, base)).isNotEmpty();
        record Probe(String purchase, String body, boolean grounded) { }
        for (Probe probe : List.of(new Probe("Plan USD 50", "Paga U5D 100", false),
                new Probe("Plan USD 100", "Paga U5D 100", true), new Probe("Plan JPY100", "Paga JPY100", true),
                new Probe("Plan USD 50", "Paga USD100", false))) {
            EvalCase evalCase = new EvalCase(base.id(), base.family(), base.description(), base.displayName(),
                    base.locale(), new EvalCase.Setup(null, false, "GRANTED", false, null, null,
                            List.of(new EvalCase.PurchaseSpec(40, probe.purchase()))),
                    base.request(), base.script(), base.expect(), base.rubricHints());
            var grounding = new io.github.stevdrey.dokene.ai.domain.DraftGroundingContext("Ana", null,
                    List.of(probe.purchase()), List.of(), "DUE", "2026-09-29");
            MessageDraft draft = draft(probe.body());
            assertThat(DraftSafetyValidator.validate(draft, probe.purchase(), grounding).isEmpty())
                    .as("production %s | %s", probe.body(), probe.purchase()).isEqualTo(probe.grounded());
            assertThat(InvariantChecker.contentViolations(draft, evalCase).isEmpty())
                    .as("independent %s | %s", probe.body(), probe.purchase()).isEqualTo(probe.grounded());
        }
    }

    @Test
    void deliveredLeetRefusalNegationIsNeutralisedByBoth() {
        EvalCase base = EvalDatasetLoader.loadDefault().cases().stream().filter(c -> c.id().equals("mf-02"))
                .findFirst().orElseThrow();
        var safe = new io.github.stevdrey.dokene.ai.domain.NoDraft(
                io.github.stevdrey.dokene.ai.domain.NoDraftReason.INSUFFICIENT_HISTORY,
                "No hay d3scuento disponible", RecommendationConfidence.of(0.9));
        var unsafe = new io.github.stevdrey.dokene.ai.domain.NoDraft(
                io.github.stevdrey.dokene.ai.domain.NoDraftReason.INSUFFICIENT_HISTORY,
                "No hay d3scuento, pero tienes d3scuento", RecommendationConfidence.of(0.9));
        assertThat(DraftSafetyValidator.validate(safe, "")).isEmpty();
        assertThat(InvariantChecker.contentViolations(safe, base)).isEmpty();
        assertThat(DraftSafetyValidator.validate(unsafe, "")).isPresent();
        assertThat(InvariantChecker.contentViolations(unsafe, base)).isNotEmpty();
    }

    @Test
    void spacedPercentMarkersGroundTheSameWayInBoth() {
        record Probe(String purchase, String body) { }
        for (Probe probe : List.of(new Probe("Con 10 % de descuento", "Con 10 por ciento de descuento"),
                new Probe("Con 10 por ciento de descuento", "Con 10 % de descuento"),
                new Probe("Con 10% de descuento", "Con 10 pct de descuento"))) {
            EvalCase grounded = EvalDatasetLoader.loadDefault().cases().stream().filter(c -> c.id().equals("mf-02"))
                    .map(c -> new EvalCase(c.id(), c.family(), c.description(), c.displayName(), c.locale(),
                            new EvalCase.Setup(null, false, "GRANTED", false, null, null,
                                    List.of(new EvalCase.PurchaseSpec(40, probe.purchase()))),
                            c.request(), c.script(), c.expect(), c.rubricHints())).findFirst().orElseThrow();
            var grounding = new io.github.stevdrey.dokene.ai.domain.DraftGroundingContext("Ana", null,
                    List.of(probe.purchase()), List.of(), "DUE", "2026-09-29");
            MessageDraft draft = draft(probe.body());
            assertThat(DraftSafetyValidator.validate(draft, probe.purchase(), grounding)).as(probe.body()).isEmpty();
            assertThat(InvariantChecker.contentViolations(draft, grounded)).as(probe.body()).isEmpty();
        }
    }

    @Test
    void deliveredRefusalNegationSurvivesZeroWidthCharacters() {
        EvalCase base = EvalDatasetLoader.loadDefault().cases().stream().filter(c -> c.id().equals("mf-02"))
                .findFirst().orElseThrow();
        var refusal = new io.github.stevdrey.dokene.ai.domain.NoDraft(
                io.github.stevdrey.dokene.ai.domain.NoDraftReason.INSUFFICIENT_HISTORY,
                "No hay una of\u200berta relevante", RecommendationConfidence.of(0.9));
        assertThat(DraftSafetyValidator.validate(refusal, "")).isEmpty();
        assertThat(InvariantChecker.contentViolations(refusal, base)).isEmpty();
    }

    @Test
    void leetSpellingGroundedByThePurchaseAndIdeographicStopsAreAcceptedByBoth() {
        EvalCase base = EvalDatasetLoader.loadDefault().cases().stream().filter(c -> c.id().equals("mf-02"))
                .findFirst().orElseThrow();
        EvalCase grounded = new EvalCase(base.id(), base.family(), base.description(), base.displayName(),
                base.locale(), new EvalCase.Setup(null, false, "GRANTED", false, null, null,
                        List.of(new EvalCase.PurchaseSpec(40, "Plan con d3scuento"))),
                base.request(), base.script(), base.expect(), base.rubricHints());
        var grounding = new io.github.stevdrey.dokene.ai.domain.DraftGroundingContext("Ana", null,
                List.of("Plan con d3scuento"), List.of(), "DUE", "2026-09-29");
        for (String body : List.of("Recuerda tu d3scuento", "Hola\u3002 Gracias por tu compra")) {
            MessageDraft draft = draft(body);
            assertThat(DraftSafetyValidator.validate(draft, "Plan con d3scuento", grounding)).as(body).isEmpty();
            assertThat(InvariantChecker.contentViolations(draft, grounded)).as(body).isEmpty();
        }
    }

    @Test
    void localeEquivalentAmountsAreAcceptedByBothProductionAndTheIndependentInvariants() {
        for (String[] pair : new String[][] {{"$10,00", "$10.00"}, {"10,5%", "10.5%"}, {"$1 250,50", "$1250.50"}}) {
            EvalCase grounded = EvalDatasetLoader.loadDefault().cases().stream().filter(c -> c.id().equals("mf-02"))
                    .map(c -> new EvalCase(c.id(), c.family(), c.description(), c.displayName(), c.locale(),
                            new EvalCase.Setup(null, false, "GRANTED", false, null, null,
                                    List.of(new EvalCase.PurchaseSpec(40, "Paquete de " + pair[0]))),
                            c.request(), c.script(), c.expect(), c.rubricHints())).findFirst().orElseThrow();
            var grounding = new io.github.stevdrey.dokene.ai.domain.DraftGroundingContext("Ana", null,
                    List.of("Paquete de " + pair[0]), List.of(), "DUE", "2026-09-29");
            MessageDraft draft = draft("Recuerda el paquete de " + pair[1]);
            assertThat(DraftSafetyValidator.validate(draft, "Paquete de " + pair[0], grounding)).as(pair[0]).isEmpty();
            assertThat(InvariantChecker.contentViolations(draft, grounded)).as(pair[0]).isEmpty();
        }
    }
}
