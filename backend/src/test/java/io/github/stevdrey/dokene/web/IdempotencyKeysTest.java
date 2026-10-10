// ABOUTME: Pins the shared Idempotency-Key grammar (spec 0002 AC-5, ADR 0023 §4.2).
package io.github.stevdrey.dokene.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeysTest {
    @ParameterizedTest
    @ValueSource(strings = {"a", "submit-1", "A.b_c:d-9"})
    void acceptsTheGrammar(String key) {
        assertThat(IdempotencyKeys.isValid(key)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "a b", "ключ", "a/b", "key\n"})
    void rejectsEverythingElse(String key) {
        assertThat(IdempotencyKeys.isValid(key)).isFalse();
    }

    @Test
    void boundsTheLengthAt128() {
        assertThat(IdempotencyKeys.isValid("x".repeat(128))).isTrue();
        assertThat(IdempotencyKeys.isValid("x".repeat(129))).isFalse();
    }
}
