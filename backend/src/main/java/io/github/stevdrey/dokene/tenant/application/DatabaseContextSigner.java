package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;

/**
 * Issues short-lived, database-verifiable context capabilities.
 */
public interface DatabaseContextSigner {

    SignedDatabaseContext issueTenantContext(TenantId tenantId);

    SignedDatabaseContext issueIdentityContext(IdentityId identityId);

    SignedDatabaseContext issueAuditContext(
            TenantId tenantId,
            IdentityId actorId,
            TenantMembershipId membershipId
    );

    /**
     * Audit capability for the trusted provider webhook path: tenant attribution with no actor or membership
     * (ADR 0023 §4.5). PostgreSQL accepts it only for {@code MESSAGE_DELIVERY_UPDATED}.
     */
    SignedDatabaseContext issueProviderAuditContext(TenantId tenantId);

    /**
     * System-scope capability for one cross-tenant maintenance function (inbox sweep, retention purge, dispatch
     * recovery). It names the task, never a tenant or identity, so it never satisfies tenant RLS.
     */
    SignedDatabaseContext issueMaintenanceContext(MaintenanceTask task);
}
