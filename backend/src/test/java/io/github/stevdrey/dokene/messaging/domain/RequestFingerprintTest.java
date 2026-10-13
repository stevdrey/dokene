// ABOUTME: Checks the canonical idempotency fingerprint: stable, order sensitive, and null differs from empty.
// ABOUTME: A changed note or decision must change the fingerprint so a key reuse is detected.
package io.github.stevdrey.dokene.messaging.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void isDeterministicLowercaseHexOfSixtyFourCharacters() {
        String first = RequestFingerprint.of(MessageOperation.SUBMIT, List.of("a", "b"));
        String second = RequestFingerprint.of(MessageOperation.SUBMIT, List.of("a", "b"));
        assertThat(first).isEqualTo(second).matches("^[0-9a-f]{64}$");
    }

    @Test
    void distinguishesOperationOrderNullAndEmpty() {
        String base = RequestFingerprint.of(MessageOperation.APPROVAL, Arrays.asList("m", "APPROVE", null, "1"));
        assertThat(RequestFingerprint.of(MessageOperation.CANCELLATION, Arrays.asList("m", "APPROVE", null, "1")))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.of(MessageOperation.APPROVAL, Arrays.asList("APPROVE", "m", null, "1")))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.of(MessageOperation.APPROVAL, Arrays.asList("m", "APPROVE", "", "1")))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.of(MessageOperation.APPROVAL, Arrays.asList("m", "REJECT", null, "1")))
                .isNotEqualTo(base);
        // Length prefixes keep "ab","c" apart from "a","bc".
        assertThat(RequestFingerprint.of(MessageOperation.SUBMIT, List.of("ab", "c")))
                .isNotEqualTo(RequestFingerprint.of(MessageOperation.SUBMIT, List.of("a", "bc")));
    }
}
