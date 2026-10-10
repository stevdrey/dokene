// ABOUTME: A provider template the gate resolved for an intent: name, language and the parameter names it takes.
// ABOUTME: The pipeline fills the parameters in this order; until feature 6 only ["body"] is fillable.
package io.github.stevdrey.dokene.messaging.application;

import java.util.List;
import java.util.Objects;

public record ResolvedTemplate(String providerTemplateName, String language, List<String> parameterNames) {
    public ResolvedTemplate {
        if (providerTemplateName == null || providerTemplateName.isBlank()) {
            throw new IllegalArgumentException("Provider template name is required");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("Template language is required");
        }
        parameterNames = List.copyOf(Objects.requireNonNull(parameterNames, "Parameter names are required"));
    }
}
