// ABOUTME: Strong numeric ETag parsing and formatting for optimistic concurrency (ADR 0023 §4.1).
// ABOUTME: Accepts only "<canonical non negative integer>"; weak tags, leading zeros and bare numbers are rejected.
package io.github.stevdrey.dokene.web;

import java.util.OptionalLong;
import java.util.regex.Pattern;

public final class StrongEtag {
    private static final Pattern STRONG_NUMERIC = Pattern.compile("\"(0|[1-9][0-9]*)\"");

    private StrongEtag() {
    }

    /** The version inside a strong numeric ETag, or empty when the value is not exactly that shape. */
    public static OptionalLong parse(String ifMatch) {
        if (ifMatch == null || !STRONG_NUMERIC.matcher(ifMatch).matches()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(ifMatch, 1, ifMatch.length() - 1, 10));
        } catch (NumberFormatException overflow) {
            return OptionalLong.empty();
        }
    }

    public static String format(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("Version cannot be negative");
        }
        return "\"" + version + "\"";
    }
}
