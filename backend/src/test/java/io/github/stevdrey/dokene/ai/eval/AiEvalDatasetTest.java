package io.github.stevdrey.dokene.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AiEvalDatasetTest {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[\\s-]?){8,}(?!\\d)");
    private static final Pattern URL = Pattern.compile("(?i)https?://([^/\\s]+)");

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
    void containsNoRealLookingPersonalDataOrLiveLinks() {
        List<String> texts = dataset.cases().stream().flatMap(c -> {
            var texts2 = new java.util.ArrayList<String>();
            texts2.add(c.displayName());
            texts2.add(c.setup().notes() == null ? "" : c.setup().notes());
            c.setup().purchases().forEach(p -> texts2.add(p.description()));
            if (c.script().draft() != null && c.script().draft().body() != null) {
                texts2.add(c.script().draft().body());
            }
            return texts2.stream();
        }).toList();
        assertThat(texts).allSatisfy(text -> {
            assertThat(EMAIL.matcher(text).find()).as("email in %s", text).isFalse();
            assertThat(PHONE.matcher(text).find()).as("phone number in %s", text).isFalse();
            var urls = URL.matcher(text);
            while (urls.find()) {
                assertThat(urls.group(1)).as("only reserved .test hosts allowed").endsWith(".test");
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
