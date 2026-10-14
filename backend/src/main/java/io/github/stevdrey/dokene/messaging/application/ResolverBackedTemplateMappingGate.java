// ABOUTME: Answers the submit-time template gate from the tenant's verified template registry (ADR 0025).
// ABOUTME: MAPPED only when the registry resolves a currently sendable mapping; everything else fails closed.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ResolverBackedTemplateMappingGate implements TemplateMappingGate {
    private final TemplateMappingResolver resolver;

    public ResolverBackedTemplateMappingGate(TemplateMappingResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "Template resolver is required");
    }

    @Override
    public TemplateMappingStatus check(SemanticTemplateIntent intent) {
        Objects.requireNonNull(intent, "Template intent is required");
        return resolver.resolve(intent) instanceof TemplateMappingResolver.Resolved ? TemplateMappingStatus.MAPPED
                : TemplateMappingStatus.NOT_MAPPED;
    }
}
