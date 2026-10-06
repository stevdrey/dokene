package io.github.stevdrey.dokene.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AiEvalDatasetTest {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)\\+?(?:\\(?\\d\\)?[\\s.-]?){8,}(?!\\d)");

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
        dataset.cases().forEach(c -> collectTexts(c, texts));
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
        collectTexts(polluted, texts);
        assertThatThrownBy(() -> assertNoRealData(texts)).isInstanceOf(AssertionError.class);

        for (String bad : List.of("Llama al +506 8888 0000", "Llama al +1 (212) 555-1234", "Tel 8888.0000", "Tel (506) 8888-0000", "Visita https://tienda-real.com/oferta", "Compra en www.real-shop.com", "Mira promo.dev hoy", "Baja ftp://real-shop.com/file", "Ver tienda.com/promo", "mail a ana@gmail.com")) {
            assertThatThrownBy(() -> assertNoRealData(List.of(bad))).as(bad).isInstanceOf(AssertionError.class);
        }
        assertNoRealData(List.of("Visita https://promo.example.test/50", "Hola, ¿cómo te fue con tu café?"));
    }

    /** Collects every String reachable from a record graph (records, lists, maps), so no text field is skipped. */
    static void collectTexts(Object value, List<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof String text) {
            out.add(text);
        } else if (value instanceof java.util.Collection<?> items) {
            items.forEach(item -> collectTexts(item, out));
        } else if (value instanceof java.util.Map<?, ?> map) {
            map.forEach((k, v) -> {
                collectTexts(k, out);
                collectTexts(v, out);
            });
        } else if (value.getClass().isRecord()) {
            for (var component : value.getClass().getRecordComponents()) {
                try {
                    collectTexts(component.getAccessor().invoke(value), out);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
    }

    /** Host of a detected link: scheme, credentials, port, path, query and fragment removed. */
    private static String hostOf(String link) {
        String host = link.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
        host = host.replaceFirst("^[^/@]*@", "");
        return host.split("[/:?#\\s]", 2)[0].toLowerCase(java.util.Locale.ROOT).replaceAll("[.,;]+$", "");
    }

    static void assertNoRealData(List<String> texts) {
        assertThat(texts).allSatisfy(text -> {
            assertThat(EMAIL.matcher(text).find()).as("email in %s", text).isFalse();
            assertThat(PHONE.matcher(text).find()).as("phone number in %s", text).isFalse();
            var links = InvariantChecker.LINK.matcher(text);
            while (links.find()) {
                assertThat(hostOf(links.group())).as("only reserved .test hosts allowed in %s", text).endsWith(".test");
            }
        });
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
}
