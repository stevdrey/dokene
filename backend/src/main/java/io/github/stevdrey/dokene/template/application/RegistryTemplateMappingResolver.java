package io.github.stevdrey.dokene.template.application;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.template.domain.TemplateMapping;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves only from stored provider evidence; no network call and no write, so it is safe at submit, approval and
 * send time. Anything that is not currently sendable is {@code Disabled} (fail closed).
 */
@Service
@EnableConfigurationProperties(TemplateRegistryProperties.class)
public class RegistryTemplateMappingResolver implements TemplateMappingResolver {
    private final TemplateMappingRepository repository;
    private final TemplateProviderGateway gateway;
    private final TenantContextProvider contexts;
    private final TemplateRegistryProperties properties;
    private final Clock clock;
    private final TenantRepository tenants;

    public RegistryTemplateMappingResolver(TemplateMappingRepository repository, TemplateProviderGateway gateway,
            TenantContextProvider contexts, TemplateRegistryProperties properties, Clock clock,
            TenantRepository tenants) {
        this.tenants = Objects.requireNonNull(tenants, "Tenant repository is required");
        this.repository = Objects.requireNonNull(repository, "Repository is required");
        this.gateway = Objects.requireNonNull(gateway, "Gateway is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.properties = Objects.requireNonNull(properties, "Properties are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    @Transactional(readOnly = true)
    public Resolution resolve(SemanticTemplateIntent intent) {
        Objects.requireNonNull(intent, "Intent is required");
        var tenantId = contexts.requireCurrent().tenantId();
        Optional<TemplateMapping> found = repository.find(tenantId, intent);
        if (found.isEmpty()) {
            return new NotMapped();
        }
        TemplateMapping mapping = found.get();
        Optional<TemplateProviderGateway.Sender> sender = gateway.currentSender();
        if (sender.isEmpty() || !mapping.sendable(sender.get().integrationId(), sender.get().businessAccountId(),
                clock.instant(), properties.approvalMaxAge(),
                tenants.findById(tenantId).flatMap(Tenant::operatingCurrency).orElse(null))) {
            return new Disabled();
        }
        return new Resolved(new ResolvedTemplateMapping(mapping.intent(), mapping.providerTemplateName(),
                mapping.language(), mapping.approval().parameterFormat(), mapping.parameterNames(),
                mapping.approval().content(), mapping.version()));
    }
}
