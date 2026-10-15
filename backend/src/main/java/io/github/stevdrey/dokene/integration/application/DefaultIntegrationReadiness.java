package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** Fail closed: sending is not reported as enabled until the kill-switch policy (#144) exists. */
@Component
@ConditionalOnMissingBean(value = IntegrationReadiness.class, ignored = DefaultIntegrationReadiness.class)
class DefaultIntegrationReadiness implements IntegrationReadiness {
    @Override
    public boolean outboundEnabled(TenantId tenantId) {
        return false;
    }
}
