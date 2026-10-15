// ABOUTME: Pins the one If-Match grammar every controller shares (ADR 0023 §4.1): exactly one strong canonical
// ABOUTME: numeric ETag is accepted, and every other header value, including lenient legacy forms, is refused.
package io.github.stevdrey.dokene.tenant.api;

import static org.assertj.core.api.Assertions.assertThat;

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
            "\"999999999999999999\"|999999999999999999"
    })
    void acceptsOneStrongCanonicalNumericEtag(String header, long version) {
        assertThat(StrictRequests.strongNumericVersion(header)).hasValue(version);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", " ", "0", "12", "\"\"", "\"00\"", "\"01\"", "\"+0\"", "\"-1\"", "W/\"1\"", "w/\"1\"",
            "\"1\", \"2\"", "\"1\",\"2\"", "*", " \"1\"", "\"1\" ", "\"1", "1\"", "'1'", "\"1.0\"", "\"0x1\"",
            "\"1000000000000000000\"", "\"99999999999999999999\""
    })
    void refusesEveryOtherHeaderValue(String header) {
        assertThat(StrictRequests.strongNumericVersion(header)).isEmpty();
    }

    @Test
    void formatsTheOnlyFormItAccepts() {
        assertThat(StrictRequests.strongEtag(0)).isEqualTo("\"0\"");
        assertThat(StrictRequests.strongEtag(17)).isEqualTo("\"17\"");
        assertThat(StrictRequests.strongNumericVersion(StrictRequests.strongEtag(123_456_789L))).hasValue(123_456_789L);
    }
}
