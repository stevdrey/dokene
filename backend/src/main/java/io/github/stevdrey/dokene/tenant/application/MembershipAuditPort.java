package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;

/**
 * Port for receiving notifications of tenant membership role transitions.
 */
@FunctionalInterface
public interface MembershipAuditPort {

    void roleChanged(
            TenantMembershipId membershipId,
            TenantRole previousRole,
            TenantRole newRole
    );

    static MembershipAuditPort noop() {
        return (membershipId, previousRole, newRole) -> { };
    }
}
