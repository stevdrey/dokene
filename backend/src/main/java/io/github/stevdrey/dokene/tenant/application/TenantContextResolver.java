package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;

/**
 * Establishes a trusted context by validating a requested tenant against server-side state.
 */
public interface TenantContextResolver {

    TenantContext resolve(IdentityId identityId, TenantId requestedTenantId);
}
