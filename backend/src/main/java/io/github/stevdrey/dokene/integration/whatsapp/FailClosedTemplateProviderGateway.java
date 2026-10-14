// ABOUTME: Provider gateway that reads the tenant's configured sender but never reports provider evidence.
// ABOUTME: Keeps every template unverified, hence unsendable, until the Graph API verification replaces it (ADR 0025).
package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.template.application.TemplateProviderGateway;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class FailClosedTemplateProviderGateway implements TemplateProviderGateway {
    private final WhatsAppIntegrationRepository integrations;
    private final TenantContextProvider contexts;

    public FailClosedTemplateProviderGateway(WhatsAppIntegrationRepository integrations,
            TenantContextProvider contexts) {
        this.integrations = Objects.requireNonNull(integrations, "Integrations are required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
    }

    @Override
    public Optional<Sender> currentSender() {
        return integrations.findByTenant(contexts.requireCurrent().tenantId())
                .map(i -> new Sender(i.id(), i.businessAccountId()));
    }

    @Override
    public Lookup lookup(String providerTemplateName, String language) {
        return new Unavailable();
    }
}
