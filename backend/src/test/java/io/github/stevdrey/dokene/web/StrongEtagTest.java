// ABOUTME: Pins the strict ETag grammar: only a quoted canonical non negative integer parses (spec 0002 AC-5).
// ABOUTME: Weak tags, leading zeros, bare numbers, blanks and overflow all come back empty.
package io.github.stevdrey.dokene.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StrongEtagTest {
    @Test
    void parsesCanonicalStrongValues() {
        assertThat(StrongEtag.parse("\"0\"")).hasValue(0L);
        assertThat(StrongEtag.parse("\"7\"")).hasValue(7L);
        assertThat(StrongEtag.parse("\"" + Long.MAX_VALUE + "\"")).hasValue(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"7", "W/\"7\"", "\"07\"", "\"-1\"", "\" 7\"", "\"7\" ", "\"\"", "\"abc\"",
            "\"9223372036854775808\"", "'7'"})
    void rejectsEverythingElse(String value) {
        assertThat(StrongEtag.parse(value)).isEmpty();
    }

    @Test
    void formatsRoundTrip() {
        assertThat(StrongEtag.format(12)).isEqualTo("\"12\"");
        assertThat(StrongEtag.parse(StrongEtag.format(12))).hasValue(12L);
        assertThatThrownBy(() -> StrongEtag.format(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
