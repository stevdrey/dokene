// ABOUTME: Port the template module owns to resolve a semantic intent into the tenant's provider template (ADR 0024 §3).
// ABOUTME: Supersedes messaging's TemplateMappingGate once #135 lands; only Resolved lets a submit, approval or send continue.
package io.github.stevdrey.dokene.template.application;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.template.domain.TemplateParameterFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public interface TemplateMappingResolver {
    /** Resolves within the current tenant context; the intent comes from a closed enum, never from AI text. */
    Resolution resolve(SemanticTemplateIntent intent);

    sealed interface Resolution permits Resolved, NotMapped, Disabled {
    }

    record Resolved(ResolvedTemplateMapping mapping) implements Resolution {
        public Resolved {
            Objects.requireNonNull(mapping, "Mapping is required");
        }
    }

    record NotMapped() implements Resolution {
    }

    record Disabled() implements Resolution {
    }

    /**
     * A mapping as stored for the tenant; the version feeds the approval fingerprint. {@code parameterFormat} is the
     * placeholder shape the approved provider schema was verified to declare, so the send builds parameters in it.
     */
    record ResolvedTemplateMapping(SemanticTemplateIntent intent, String providerTemplateName, String language,
            TemplateParameterFormat parameterFormat, List<String> parameterNames, long version) {
        /** Meta's template naming grammar (ADR 0023 §3.2). */
        public static final Pattern PROVIDER_TEMPLATE_NAME = Pattern.compile("^[a-z0-9_]{1,512}$");

        public ResolvedTemplateMapping {
            Objects.requireNonNull(intent, "Intent is required");
            Objects.requireNonNull(providerTemplateName, "Provider template name is required");
            Objects.requireNonNull(language, "Template language is required");
            Objects.requireNonNull(parameterFormat, "Parameter format is required");
            parameterNames = List.copyOf(Objects.requireNonNull(parameterNames, "Parameter names are required"));
            // Same name and language rules as ApprovalFingerprint.TemplateRevision, which binds this mapping.
            if (!PROVIDER_TEMPLATE_NAME.matcher(providerTemplateName).matches()) {
                throw new IllegalArgumentException("Provider template name is malformed");
            }
            if (language.isBlank()) {
                throw new IllegalArgumentException("Template language is required");
            }
            if (version < 0) {
                throw new IllegalArgumentException("Mapping version cannot be negative");
            }
        }
    }
}
