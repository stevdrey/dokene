// ABOUTME: Deterministic check of rendered parameter values against a mapping's ordered parameter schema.
// ABOUTME: Count, type (text), length and control characters; values are never logged or echoed in errors.
package io.github.stevdrey.dokene.template.domain;

import java.util.List;
import java.util.Objects;

public final class TemplateParameterValues {
    /** Maximum characters (Unicode code points, so an emoji counts once) per parameter value. */
    public static final int MAX_LENGTH = 1024;
    /** Meta rejects more than four consecutive spaces, so a run of five is the first refused. */
    private static final String FIVE_SPACES = " ".repeat(5);

    private TemplateParameterValues() {
    }

    /**
     * ISO control characters (C0, DEL, C1), the Unicode line and paragraph separators, and the invisible or
     * direction-changing format characters that could disguise a value: bidi marks, embeddings, overrides and
     * isolates, zero-width space, word joiner and the byte order mark. Zero-width (non-)joiners stay allowed because
     * emoji sequences and several scripts need them.
     */
    private static boolean forbidden(int codePoint) {
        return Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.LINE_SEPARATOR
                || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR || isInvisibleFormat(codePoint);
    }

    private static boolean isInvisibleFormat(int codePoint) {
        return codePoint == 0x061C || codePoint == 0x200B || codePoint == 0x200E || codePoint == 0x200F
                || (codePoint >= 0x202A && codePoint <= 0x202E) || codePoint == 0x2060
                || (codePoint >= 0x2066 && codePoint <= 0x2069) || codePoint == 0xFEFF;
    }

    /** Returns the values unchanged when they fit the schema; otherwise throws without quoting any value. */
    public static List<String> validate(TemplateMapping mapping, List<String> values) {
        Objects.requireNonNull(mapping, "Mapping is required");
        Objects.requireNonNull(values, "Parameter values are required");
        if (values.size() != mapping.parameterNames().size()) {
            throw new IllegalArgumentException("Parameter count does not match the template");
        }
        for (String value : values) {
            if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > MAX_LENGTH || value.contains(FIVE_SPACES)
                    || value.codePoints().anyMatch(TemplateParameterValues::forbidden)) {
                throw new IllegalArgumentException("Parameter value is not acceptable");
            }
        }
        return List.copyOf(values);
    }
}
