package io.github.stevdrey.dokene.tenant.application;

/**
 * Raised when authenticated server-side state cannot authorize a tenant context.
 */
public final class TenantContextAuthorizationException extends SecurityException {

    public TenantContextAuthorizationException() {
        super("Tenant context cannot be established");
    }
}
