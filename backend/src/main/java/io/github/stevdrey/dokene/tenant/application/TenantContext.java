package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;

/**
 * Trusted tenant boundary established from authenticated server-side membership state.
 */
public record TenantContext(
        TenantId tenantId,
        IdentityId identityId,
        TenantMembershipId membershipId,
        TenantRole role,
        TenantMembershipStatus status
) {

    public TenantContext {
        require(tenantId, "Tenant context tenant ID is required");
        require(identityId, "Tenant context identity ID is required");
        require(membershipId, "Tenant context membership ID is required");
        require(role, "Tenant context role is required");
        require(status, "Tenant context membership status is required");
    }

    public TenantContext(
            TenantId tenantId,
            IdentityId identityId,
            TenantMembershipId membershipId,
            TenantRole role
    ) {
        this(tenantId, identityId, membershipId, role, TenantMembershipStatus.ACTIVE);
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
