// ABOUTME: Deterministic check of rendered parameter values against a mapping's ordered parameter schema.
// ABOUTME: Count, type (text), length and control characters; values are never logged or echoed in errors.
package io.github.stevdrey.dokene.template.domain;

import java.util.List;
import java.util.Objects;

public final class TemplateParameterValues {
    /** Maximum characters (Unicode code points, so an emoji counts once) per parameter value. */
    public static final int MAX_LENGTH = 1024;

    private TemplateParameterValues() {
    }

    /** All ISO control characters (C0, DEL and C1) and the Unicode line and paragraph separators. */
    private static boolean forbidden(int codePoint) {
        return Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.LINE_SEPARATOR
                || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR;
    }

    /** Returns the values unchanged when they fit the schema; otherwise throws without quoting any value. */
    public static List<String> validate(TemplateMapping mapping, List<String> values) {
        Objects.requireNonNull(mapping, "Mapping is required");
        Objects.requireNonNull(values, "Parameter values are required");
        if (values.size() != mapping.parameterNames().size()) {
            throw new IllegalArgumentException("Parameter count does not match the template");
        }
        for (String value : values) {
            if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > MAX_LENGTH || value.contains("    ")
                    || value.codePoints().anyMatch(TemplateParameterValues::forbidden)) {
                throw new IllegalArgumentException("Parameter value is not acceptable");
            }
        }
        return List.copyOf(values);
    }
}
