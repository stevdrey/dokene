package io.github.stevdrey.dokene.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiEvalDatasetTest {

    private final EvalDataset dataset = EvalDatasetLoader.loadDefault();

    @Test
    void isVersionedSyntheticAndCoversEveryScenarioFamily() {
        assertThat(dataset.datasetVersion()).matches("\\d+\\.\\d+\\.\\d+");
        assertThat(dataset.syntheticOnly()).isTrue();
        assertThat(dataset.cases().stream().map(EvalCase::family).distinct().toList())
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(EvalCase.Family.class));
    }

    @Test
    void containsPositiveNegativeAndAdversarialCases() {
        assertThat(dataset.cases()).anyMatch(c -> "AVAILABLE".equals(c.expect().recommendationStatus()));
        assertThat(dataset.cases()).anyMatch(c -> "INELIGIBLE".equals(c.expect().recommendationStatus()));
        assertThat(dataset.cases()).anyMatch(c -> c.expect().contactForbidden());
        assertThat(dataset.cases()).anyMatch(c -> c.family() == EvalCase.Family.ADVERSARIAL_NOTES);
        assertThat(dataset.cases()).anyMatch(c -> "AI_UNAVAILABLE".equals(c.expect().draftStatus()));
    }

    @Test
    void usesUniqueIdsAndDisplayNamesThatAreMarkedAsDemoData() {
        assertThat(dataset.cases().stream().map(EvalCase::id).distinct().count()).isEqualTo(dataset.cases().size());
        assertThat(dataset.cases()).allSatisfy(c -> assertThat(c.displayName()).contains("Demo-"));
    }

    @Test
    void containsNoRealLookingPersonalDataOrLiveLinksInAnyTextField() {
        List<String> texts = new java.util.ArrayList<>();
        dataset.cases().forEach(c -> SyntheticDataGuard.collectTexts(c, texts));
        assertThat(texts).as("traversal reaches scripted and expectation fields").hasSizeGreaterThan(dataset.cases().size() * 8);
        assertNoRealData(texts);
    }

    @Test
    void theGuardCatchesRealLookingDataInScriptedFieldsThatAreNotSetupOrDraftBody() {
        EvalCase base = dataset.cases().stream().filter(c -> c.id().equals("rp-01")).findFirst().orElseThrow();
        var rec = base.script().recommendation();
        EvalCase polluted = new EvalCase(base.id(), base.family(), base.description(), base.displayName(), base.locale(),
                base.setup(), base.request(), new EvalCase.Script(new EvalCase.RecommendationScript(rec.kind(), rec.action(),
                rec.templateIntent(), rec.reason(), rec.rationale(), rec.confidence(), rec.failure(),
                java.util.Map.of("contact", "ventas@tienda-real.com"), rec.rejection()), base.script().draft()),
                base.expect(), base.rubricHints());
        List<String> texts = new java.util.ArrayList<>();
        SyntheticDataGuard.collectTexts(polluted, texts);
        assertThatThrownBy(() -> assertNoRealData(texts)).isInstanceOf(AssertionError.class);

        for (String bad : List.of("Llama al +506 8888 0000", "Llama al +506/8888/0000", "Llama al +506 8888\u20130000", "Llama al +506\u20118888\u20110000", "Llama al +506\u00a08888\u00a00000", "Llama al +506\u202f8888\u202f0000", "Llama al +506\u20078888\u20070000", "Llama al +1 (212) 555-1234", "Tel 8888.0000", "Tel (506) 8888-0000", "Visita https://tienda-real.com/oferta", "Compra en www.real-shop.com", "Mira promo.dev hoy", "Baja ftp://real-shop.com/file", "Ver tienda.com/promo", "https://evil.com?redirect=@safe.test", "https://evil.com#@safe.test", "https://safe.test@evil.com/x", "https://evil.com\\@safe.test", "mail a ana@gmail.com")) {
            assertThatThrownBy(() -> assertNoRealData(List.of(bad))).as(bad).isInstanceOf(AssertionError.class);
        }
        assertNoRealData(List.of("Visita https://promo.example.test:8443/x?a=1#f", "Visita https://promo.example.test/50", "Hola, ¿cómo te fue con tu café?"));
    }

    private static void assertNoRealData(List<String> texts) {
        assertThat(SyntheticDataGuard.violations(texts)).as("synthetic-data violations").isEmpty();
    }

    @Test
    void loaderRejectsDatasetsThatAreNotSyntheticOrMissFamilies() {
        assertThatThrownBy(() -> EvalDatasetLoader.validate(
                new EvalDataset("1.0.0", false, "x", dataset.cases()))).hasMessageContaining("syntheticOnly");
        assertThatThrownBy(() -> EvalDatasetLoader.validate(
                new EvalDataset("1.0.0", true, "x", dataset.cases().subList(0, 2)))).hasMessageContaining("families");
        assertThatThrownBy(() -> EvalDatasetLoader.validate(new EvalDataset("1.0.0", true, "x",
                List.of(dataset.cases().getFirst(), dataset.cases().getFirst())))).hasMessageContaining("Duplicate");
    }

    @Test
    void theLoaderRejectsRealLookingDataBeforeAnyProviderCanSeeIt() {
        EvalCase base = dataset.cases().getFirst();
        EvalCase.Setup setup = base.setup();
        for (String bad : List.of("Escribe a ana@gmail.com", "Llama al +506 8888 0000", "Visita www.tienda-real.com")) {
            EvalCase polluted = new EvalCase(base.id(), base.family(), base.description(), base.displayName(),
                    base.locale(), new EvalCase.Setup(bad, setup.archived(), setup.consent(),
                    setup.doNotContact(), setup.customerCadenceDays(), setup.explicitNextFollowUpDaysFromToday(),
                    setup.purchases()), base.request(), base.script(), base.expect(), base.rubricHints());
            List<EvalCase> cases = new java.util.ArrayList<>(dataset.cases());
            cases.set(0, polluted);

            assertThatThrownBy(() -> EvalDatasetLoader.validate(
                    new EvalDataset(dataset.datasetVersion(), true, dataset.description(), cases)))
                    .as(bad).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(base.id()).hasMessageNotContaining(bad);
        }
    }

    @Test
    void theLoaderRejectsCustomerNamesWithoutTheDemoMarker() {
        EvalCase base = dataset.cases().getFirst();
        EvalCase real = new EvalCase(base.id(), base.family(), base.description(), "Lucía Fernández", base.locale(),
                base.setup(), base.request(), base.script(), base.expect(), base.rubricHints());
        List<EvalCase> cases = new java.util.ArrayList<>(dataset.cases());
        cases.set(0, real);

        assertThatThrownBy(() -> EvalDatasetLoader.validate(
                new EvalDataset(dataset.datasetVersion(), true, dataset.description(), cases)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(base.id())
                .hasMessageContaining(SyntheticDataGuard.DISPLAY_NAME_VIOLATION)
                .hasMessageNotContaining("Fernández");
    }
}
