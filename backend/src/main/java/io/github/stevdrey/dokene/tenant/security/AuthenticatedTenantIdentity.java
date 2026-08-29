package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;

/**
 * Minimal contract an authentication adapter must expose before tenant context can be established.
 */
public interface AuthenticatedTenantIdentity {

    IdentityId identityId();
}
