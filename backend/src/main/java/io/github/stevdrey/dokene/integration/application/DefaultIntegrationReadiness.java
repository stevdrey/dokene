package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** Fail closed: neither sending nor webhook readiness is reported until the owning features exist. */
@Component
@ConditionalOnMissingBean(value = IntegrationReadiness.class, ignored = DefaultIntegrationReadiness.class)
class DefaultIntegrationReadiness implements IntegrationReadiness {
    @Override
    public boolean outboundEnabled(TenantId tenantId) {
        return false;
    }

    @Override
    public boolean webhookVerified(TenantId tenantId) {
        return false;
    }
}
