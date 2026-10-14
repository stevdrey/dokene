package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;

/**
 * Runtime state reported next to the stored configuration (ADR 0023 §3.3). The tenant kill switch (#144) and webhook
 * verification (#139) supply real implementations; until then the default reports both as off.
 */
public interface IntegrationReadiness {
    boolean outboundEnabled(TenantId tenantId);

    boolean webhookVerified(TenantId tenantId);
}
