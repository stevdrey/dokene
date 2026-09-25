package io.github.stevdrey.dokene.ai.domain;

import java.util.regex.Pattern;

/** Shared non-blank rule for the domain and the generated JSON Schema. */
final class RecommendationRationale {
    // Explicit ECMAScript whitespace, so Java and JSON Schema agree on the same character set.
    private static final String WHITESPACE_CODE_POINTS =
            "\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";
    static final String NON_WHITESPACE_PATTERN = "[^" + WHITESPACE_CODE_POINTS + "]";
    private static final Pattern NON_WHITESPACE = Pattern.compile(NON_WHITESPACE_PATTERN);
    private static final String WHITESPACE_CLASS = "[" + WHITESPACE_CODE_POINTS + "]";
    private static final Pattern BOUNDARY_WHITESPACE = Pattern.compile(
            "^" + WHITESPACE_CLASS + "+|" + WHITESPACE_CLASS + "+$");

    private RecommendationRationale() {}

    static boolean isNonBlank(String value) {
        return value != null && NON_WHITESPACE.matcher(value).find();
    }

    static String trim(String value) {
        return BOUNDARY_WHITESPACE.matcher(value).replaceAll("");
    }
}
