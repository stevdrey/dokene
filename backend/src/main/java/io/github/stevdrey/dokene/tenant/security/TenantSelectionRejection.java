package io.github.stevdrey.dokene.tenant.security;

/**
 * Why {@link TenantContextRequestFilter} refused a tenant selection. The status is owned by the tenant module and is
 * the same for every route; a {@link TenantSelectionRejectionResponder} only chooses the body.
 */
public enum TenantSelectionRejection {
    /** Duplicated or malformed {@code X-Tenant-Id}. */
    INVALID_SELECTOR(400),
    /** Missing {@code X-Tenant-Id} or no active membership in the requested tenant. */
    ACCESS_DENIED(403);

    private final int status;

    TenantSelectionRejection(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
