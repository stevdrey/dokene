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

    /** The invitee accepted; the current tenant context is the invitee's newly active membership. */
    void invitationAccepted(
            TenantMembershipId membershipId,
            TenantRole role
    );

    /** A revoked membership was returned to {@code INVITED} by an authorized member. */
    void membershipReactivated(
            TenantMembershipId membershipId,
            TenantRole role
    );

    static MembershipAuditPort noop() {
        return new MembershipAuditPort() {
            @Override
            public void roleChanged(TenantMembershipId membershipId, TenantRole previousRole, TenantRole newRole) { }

            @Override
            public void membershipCreated(TenantMembershipId membershipId, TenantRole role) { }

            @Override
            public void membershipRevoked(TenantMembershipId membershipId) { }

            @Override
            public void invitationAccepted(TenantMembershipId membershipId, TenantRole role) { }

            @Override
            public void membershipReactivated(TenantMembershipId membershipId, TenantRole role) { }
        };
    }
}
