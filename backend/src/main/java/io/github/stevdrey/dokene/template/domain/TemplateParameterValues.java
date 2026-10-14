// ABOUTME: Deterministic check of rendered parameter values against a mapping's ordered parameter schema.
// ABOUTME: Count, type (text), length and control characters; values are never logged or echoed in errors.
package io.github.stevdrey.dokene.template.domain;

import java.util.List;
import java.util.Objects;

public final class TemplateParameterValues {
    public static final int MAX_LENGTH = 1024;

    private TemplateParameterValues() {
    }

    /** Returns the values unchanged when they fit the schema; otherwise throws without quoting any value. */
    public static List<String> validate(TemplateMapping mapping, List<String> values) {
        Objects.requireNonNull(mapping, "Mapping is required");
        Objects.requireNonNull(values, "Parameter values are required");
        if (values.size() != mapping.parameterNames().size()) {
            throw new IllegalArgumentException("Parameter count does not match the template");
        }
        for (String value : values) {
            if (value == null || value.isEmpty() || value.length() > MAX_LENGTH || value.contains("    ")
                    || value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                throw new IllegalArgumentException("Parameter value is not acceptable");
            }
        }
        return List.copyOf(values);
    }
}
