// ABOUTME: The Idempotency-Key grammar every Phase 3 POST accepts (ADR 0023 §4.2, ADR 0013).
// ABOUTME: One regex, shared, so no controller carries its own copy.
package io.github.stevdrey.dokene.web;

import java.util.regex.Pattern;

public final class IdempotencyKeys {
    private static final Pattern GRAMMAR = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private IdempotencyKeys() {
    }

    public static boolean isValid(String key) {
        return key != null && GRAMMAR.matcher(key).matches();
    }
}
