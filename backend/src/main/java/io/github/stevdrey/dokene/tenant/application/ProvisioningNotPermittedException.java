// ABOUTME: Thrown when the provisioning policy refuses an identity (spec 0021): provisioning is disabled, or the
// ABOUTME: identity is not on the allowlist. The API answers 403 with code PROVISIONING_NOT_PERMITTED.
package io.github.stevdrey.dokene.tenant.application;

public class ProvisioningNotPermittedException extends TenantAccessDeniedException {

    public ProvisioningNotPermittedException() {
        super("Workspace provisioning is not permitted for this identity");
    }
}
