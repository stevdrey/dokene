package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;

/**
 * Authorization policy governing workspace provisioning requests.
 */
public interface ProvisioningAuthorizationPolicy {

    boolean isAllowed(IdentityId identityId);
}
