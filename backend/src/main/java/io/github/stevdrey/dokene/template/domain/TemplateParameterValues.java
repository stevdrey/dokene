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
     * Refuses ISO control characters (C0, DEL, C1), the Unicode line and paragraph separators and, by default, every
     * Unicode FORMAT character (soft hyphen, bidi controls, zero-width space, word joiner, byte order mark, the
     * deprecated U+206A..U+206F and so on), since they hide or reorder what the customer reads. The exceptions are
     * the ones real text needs: zero-width (non-)joiners for emoji sequences and several scripts, and the tag block
     * U+E0020..U+E007F that subdivision-flag emoji are built from.
     */
    private static boolean forbidden(int codePoint) {
        return Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.LINE_SEPARATOR
                || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR || isDisallowedFormat(codePoint);
    }

    private static boolean isDisallowedFormat(int codePoint) {
        if (Character.getType(codePoint) != Character.FORMAT) {
            return false;
        }
        boolean joiner = codePoint == 0x200C || codePoint == 0x200D;
        boolean emojiTag = codePoint >= 0xE0020 && codePoint <= 0xE007F;
        return !joiner && !emojiTag;
    }

    /** Returns the values unchanged when they fit the schema; otherwise throws without quoting any value. */
    public static List<String> validate(TemplateMapping mapping, List<String> values) {
        Objects.requireNonNull(mapping, "Mapping is required");
        return validate(mapping.parameterNames(), values);
    }

    /** The same check against a resolved mapping's ordered parameter names. */
    public static List<String> validate(List<String> parameterNames, List<String> values) {
        Objects.requireNonNull(parameterNames, "Parameter names are required");
        Objects.requireNonNull(values, "Parameter values are required");
        if (values.size() != parameterNames.size()) {
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
