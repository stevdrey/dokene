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
    void normalizesInternationalIddPrefixedNumber() {
        assertThat(normalizer.normalize("0034 612 345 678", "ES")).isEqualTo("+34612345678");
    }

    @Test
    void normalizesVanityNumber() {
        assertThat(normalizer.normalize("1-800-FLOWERS", "US")).isEqualTo("+18003569377");
    }

    @Test
    void normalizesNumberWithExtension() {
        assertThat(normalizer.normalize("+1 202-555-0123 ext. 456", "US")).isEqualTo("+12025550123");
        assertThat(normalizer.normalize("202-555-0123 ext 456", "US")).isEqualTo("+12025550123");
        assertThat(normalizer.normalize("202-555-0123 x456", "US")).isEqualTo("+12025550123");
        assertThat(normalizer.normalize("202-555-0123 extension 456", "US")).isEqualTo("+12025550123");
        assertThat(normalizer.normalize("202-555-0123 #456", "US")).isEqualTo("+12025550123");
        assertThat(normalizer.normalize("202-555-0123, 456", "US")).isEqualTo("+12025550123");
        assertThat(normalizer.normalize("202-555-0123; 456", "US")).isEqualTo("+12025550123");
        assertThat(normalizer.normalize("2025550123x456", "US")).isEqualTo("+12025550123");
        assertThat(normalizer.normalize("1-800-FLOWERS ext. 123", "US")).isEqualTo("+18003569377");
        assertThat(normalizer.normalize("+1 202-555-0123;ext=456", "US")).isEqualTo("+12025550123");
        assertThat(normalizer.normalize("202-555-0123;ext=456", "US")).isEqualTo("+12025550123");
    }

    @Test
    void normalizesUnicodeDigits() {
        assertThat(normalizer.normalize("٩٨٤٥٢١١٩٠", "CL")).isEqualTo("+56984521190");
        assertThat(normalizer.normalize("+56 ٩٨٤٥٢١١٩٠", "CL")).isEqualTo("+56984521190");
        assertThat(normalizer.normalize("９８４５２１１９０", "CL")).isEqualTo("+56984521190");
        assertThat(normalizer.normalize("९८४५२११९०", "CL")).isEqualTo("+56984521190");
        assertThat(normalizer.normalize("𝟡𝟠𝟜𝟝𝟚𝟙𝟙𝟡𝟘", "CL")).isEqualTo("+56984521190");
        assertThat(normalizer.normalize("＋56 984521190", "CL")).isEqualTo("+56984521190");
        assertThat(normalizer.normalize("＋５６ ９８４５２１１９０", "CL")).isEqualTo("+56984521190");
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
