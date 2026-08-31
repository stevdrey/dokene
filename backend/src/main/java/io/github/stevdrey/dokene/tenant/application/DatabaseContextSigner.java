package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;

/**
 * Issues short-lived, database-verifiable context capabilities.
 */
public interface DatabaseContextSigner {

    SignedDatabaseContext issueTenantContext(TenantId tenantId);

    SignedDatabaseContext issueIdentityContext(IdentityId identityId);
}
