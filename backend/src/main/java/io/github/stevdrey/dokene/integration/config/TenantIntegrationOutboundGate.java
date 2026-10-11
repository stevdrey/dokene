// ABOUTME: The OutboundGate of spec 0003 step 4, read from the tenant integration row on every send (spec 0005 AC-6).
// ABOUTME: Plain read under RLS, no lock, no cache, so a flipped switch is honoured by the next send.
package io.github.stevdrey.dokene.integration.config;

import io.github.stevdrey.dokene.messaging.application.OutboundGate;
import io.github.stevdrey.dokene.messaging.application.OutboundGateStatus;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class TenantIntegrationOutboundGate implements OutboundGate {
    private final TenantMessagingIntegrationRepository repository;
    private final TenantContextProvider contexts;

    public TenantIntegrationOutboundGate(TenantMessagingIntegrationRepository repository,
            TenantContextProvider contexts) {
        this.repository = Objects.requireNonNull(repository, "Repository is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
    }

    @Override
    public OutboundGateStatus check() {
        return contexts.currentTenantId()
                .flatMap(tenantId -> repository.findByTenant(tenantId.value()))
                .map(TenantIntegrationOutboundGate::status)
                .orElse(OutboundGateStatus.INTEGRATION_DISABLED);
    }

    static OutboundGateStatus status(TenantMessagingIntegration row) {
        if (!row.enabled()) {
            return OutboundGateStatus.INTEGRATION_DISABLED;
        }
        return row.outboundEnabled() ? OutboundGateStatus.ENABLED : OutboundGateStatus.OUTBOUND_DISABLED_FOR_TENANT;
    }
}
