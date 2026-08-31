package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.List;
import java.util.Objects;

/**
 * Narrow bootstrap query for tenants that an authenticated identity may enter.
 */
public interface TenantMembershipDiscovery {

    List<ActiveTenantMembership> findActiveMemberships(IdentityId identityId);

    record ActiveTenantMembership(
            TenantId tenantId,
            String tenantDisplayName,
            TenantMembershipId membershipId,
            TenantRole role
    ) {

        public ActiveTenantMembership {
            Objects.requireNonNull(tenantId, "Tenant ID is required");
            Objects.requireNonNull(tenantDisplayName, "Tenant display name is required");
            Objects.requireNonNull(membershipId, "Tenant membership ID is required");
            Objects.requireNonNull(role, "Tenant role is required");
        }
    }
}
