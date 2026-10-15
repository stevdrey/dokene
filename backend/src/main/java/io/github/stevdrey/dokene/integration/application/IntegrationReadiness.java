package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;

/**
 * Runtime state reported next to the stored configuration (ADR 0023 §3.3). The tenant kill switch (#144) supplies the
 * real implementation; until then the default reports sending as off. {@code webhookVerified} is not runtime state: it
 * is the persisted ownership proof on the integration itself (issue 210).
 */
public interface IntegrationReadiness {
    boolean outboundEnabled(TenantId tenantId);
}
