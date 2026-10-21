// ABOUTME: Invented canary secrets for the spec 0006, 0007 and 0009 tests: random per run values, plus the fixed literals checked in fixtures must use.
// ABOUTME: The leakage check is boolean on purpose: a failing assertion must never print a canary into a test report.
package io.github.stevdrey.dokene.integration.testsupport;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class SecretCanaries {
    /** The fixed literals the security case table names; checked in fixture bytes cannot carry a per run value. */
    public static final String TOKEN = "CANARY-TOKEN";
    public static final String PHONE = "CANARY-PHONE";
    public static final String BODY = "CANARY-BODY";

    private SecretCanaries() {
    }

    /** The three fixed literals, in the order the security case table names them. */
    public static List<String> fixedNeedles() {
        return List.of(TOKEN, PHONE, BODY);
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
