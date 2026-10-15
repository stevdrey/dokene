// ABOUTME: Pins the one If-Match grammar every controller shares (ADR 0023 §4.1): exactly one strong canonical
// ABOUTME: numeric ETag is accepted, and every other header value, including lenient legacy forms, is refused.
package io.github.stevdrey.dokene.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class StrictRequestsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"0\"|0",
            "\"1\"|1",
            "\"42\"|42",
            "\"999999999999999999\"|999999999999999999",
            "\"1000000000000000000\"|1000000000000000000",
            "\"9223372036854775807\"|9223372036854775807"
    })
    void acceptsOneStrongCanonicalNumericEtag(String header, long version) {
        assertThat(StrictRequests.strongNumericVersion(header)).hasValue(version);
    }

    /** Padded values are refused here; in a real request the servlet container already trims header whitespace. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", " ", "0", "12", "\"\"", "\"00\"", "\"01\"", "\"+0\"", "\"-1\"", "W/\"1\"", "w/\"1\"",
            "\"1\", \"2\"", "\"1\",\"2\"", "*", " \"1\"", "\"1\" ", "\"1", "1\"", "'1'", "\"1.0\"", "\"0x1\"",
            "\"9223372036854775808\"", "\"9999999999999999999\"", "\"99999999999999999999\""
    })
    void refusesEveryOtherHeaderValue(String header) {
        assertThat(StrictRequests.strongNumericVersion(header)).isEmpty();
    }

    @Test
    void formatsTheOnlyFormItAccepts() {
        assertThat(StrictRequests.strongEtag(0)).isEqualTo("\"0\"");
        assertThat(StrictRequests.strongEtag(17)).isEqualTo("\"17\"");
        assertThat(StrictRequests.strongNumericVersion(StrictRequests.strongEtag(123_456_789L))).hasValue(123_456_789L);
        assertThat(StrictRequests.strongNumericVersion(StrictRequests.strongEtag(Long.MAX_VALUE)))
                .hasValue(Long.MAX_VALUE);
    }

    @Test
    void refusesToFormatANegativeVersionItCouldNotParseBack() {
        assertThatThrownBy(() -> StrictRequests.strongEtag(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
