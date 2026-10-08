package io.github.stevdrey.dokene.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RationaleLanguageTest {
    @Test
    void recognisesSpanishRationale() {
        assertThat(RationaleLanguage.looksSpanish(
                "El cliente es elegible y está atrasado; su compra de harina fue hace 32 días.")).isTrue();
        assertThat(RationaleLanguage.looksSpanish("Han pasado más días que la cadencia habitual desde la última compra."))
                .isTrue();
    }

    @Test
    void flagsEnglishRationale() {
        assertThat(RationaleLanguage.looksSpanish(
                "The customer is eligible and overdue; their flour and vanilla purchase was 32 days ago, "
                        + "making a replenishment follow-up timely.")).isFalse();
    }

    @Test
    void wordsSharedWithEnglishAreNeutral() {
        assertThat(RationaleLanguage.looksSpanish("No trusted context provided")).isFalse();
        assertThat(RationaleLanguage.looksSpanish("No se proporcionó contexto confiable")).isTrue();
        assertThat(RationaleLanguage.looksSpanish("No hay acciones permitidas para el cliente")).isTrue();
    }

    @Test
    void nullOrBlankIsNotSpanish() {
        assertThat(RationaleLanguage.looksSpanish(null)).isFalse();
        assertThat(RationaleLanguage.looksSpanish("  ")).isFalse();
    }
}
