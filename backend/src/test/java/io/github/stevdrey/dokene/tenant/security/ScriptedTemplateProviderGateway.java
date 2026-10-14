// ABOUTME: Test stand-in for Meta: the test chooses what the provider reports, while the sender is the real stored one.
// ABOUTME: Shared by the template registry and registry-backed gate integration tests (issue 135).
package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.template.application.TemplateProviderGateway;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

final class ScriptedTemplateProviderGateway implements TemplateProviderGateway {
    private final WhatsAppIntegrationRepository integrations;
    private final TenantContextProvider contexts;
    volatile Lookup next = new Unavailable();
    /** Runs inside lookup, before it answers: lets a test land a newer refresh while this one is in flight. */
    volatile Runnable duringLookup = () -> { };

    ScriptedTemplateProviderGateway(WhatsAppIntegrationRepository integrations, TenantContextProvider contexts) {
        this.integrations = integrations;
        this.contexts = contexts;
    }

    @Override
    public Optional<Sender> currentSender() {
        return integrations.findByTenant(contexts.requireCurrent().tenantId())
                .map(i -> Sender.of(i.id(), i.businessAccountId()));
    }

    @Override
    public Lookup lookup(Sender sender, String providerTemplateName, String language) {
        Lookup answer = next;
        duringLookup.run();
        return answer;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        ScriptedTemplateProviderGateway scriptedTemplateProviderGateway(WhatsAppIntegrationRepository integrations,
                TenantContextProvider contexts) {
            return new ScriptedTemplateProviderGateway(integrations, contexts);
        }
    }
}
