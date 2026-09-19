package io.github.stevdrey.dokene.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhoneNormalizerTest {
    private final PhoneNormalizer normalizer = new PhoneNormalizer();

    @Test
    void normalizesNationalNumberWithExplicitRegion() {
        assertThat(normalizer.normalize("8888 7777", "cr")).isEqualTo("+50688887777");
    }

    @Test
    void normalizesCarrierPrefixedBrazilianNumber() {
        assertThat(normalizer.normalize("0 21 11 99999-8888", "BR")).isEqualTo("+5511999998888");
    }

    @Test
    void normalizesPeruvianFixedLineNumber() {
        assertThat(normalizer.normalize("+51 1 5173501", "PE")).isEqualTo("+5115173501");
        assertThat(normalizer.normalize("1 5173501", "PE")).isEqualTo("+5115173501");
    }

    @Test
    void rejectsMissingUnsupportedAmbiguousAndOverlongInput() {
        assertThatThrownBy(() -> normalizer.normalize("8888 7777", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normalizer.normalize("8888 7777", "ZZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normalizer.normalize("123", "CR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normalizer.normalize("1".repeat(65), "CR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normalizer.normalize("+14155552671", "CR")).isInstanceOf(IllegalArgumentException.class);
    }
}
