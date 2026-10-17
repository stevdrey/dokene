// ABOUTME: Invented canary secrets and references for spec 0006 tests, random per run so no constant can false match.
// ABOUTME: The leakage check is boolean on purpose: a failing assertion must never print a canary into a test report.
package io.github.stevdrey.dokene.integration.secrets;

import java.util.Collection;
import java.util.UUID;

public final class SecretCanaries {
    private SecretCanaries() {
    }

    /** A secret value that no dictionary word or log template can contain by accident. */
    public static String value() {
        return "CANARY-" + UUID.randomUUID();
    }

    /** A bare key in the reference grammar, unique per call. */
    public static String ref() {
        return "canary-ref-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static boolean containsAny(String text, Collection<String> needles) {
        if (text == null) {
            return false;
        }
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
