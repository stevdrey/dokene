package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;

/**
 * Port for receiving notifications of tenant membership role transitions.
 */
public interface MembershipAuditPort {

    void roleChanged(
            TenantMembershipId membershipId,
            TenantRole previousRole,
            TenantRole newRole
    );

    void membershipCreated(
            TenantMembershipId membershipId,
            TenantRole role
    );

    void membershipRevoked(
            TenantMembershipId membershipId
    );

    static MembershipAuditPort noop() {
        return new MembershipAuditPort() {
            @Override
            public void roleChanged(TenantMembershipId membershipId, TenantRole previousRole, TenantRole newRole) { }

            @Override
            public void membershipCreated(TenantMembershipId membershipId, TenantRole role) { }

            @Override
            public void membershipRevoked(TenantMembershipId membershipId) { }
        };
    }
}
