package io.github.stevdrey.dokene.tenant.application;

public final class TenantContextUnavailableException extends IllegalStateException {

    public TenantContextUnavailableException() {
        super("No active tenant context is available");
    }
}
