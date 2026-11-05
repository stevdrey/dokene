// ABOUTME: Identity-scoped port for the invitee's side of a membership invitation: list the pending ones and accept one.
// ABOUTME: The invitee has no tenant context yet, so the adapter verifies a signed identity capability in PostgreSQL.
package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface TenantInvitationDirectory {

    /** The identity's {@code INVITED} memberships in active tenants, oldest first. */
    List<PendingInvitation> findPendingInvitations(IdentityId identityId);

    /**
     * Turns the identity's pending invitation to {@code tenantId} into an active membership in the current
     * transaction, or returns empty when there is none to accept.
     */
    Optional<TenantMembershipDiscovery.ActiveTenantMembership> accept(IdentityId identityId, TenantId tenantId);

    record PendingInvitation(
            TenantId tenantId,
            String tenantDisplayName,
            TenantMembershipId membershipId,
            TenantRole role,
            Instant invitedAt
    ) {

        public PendingInvitation {
            Objects.requireNonNull(tenantId, "Tenant ID is required");
            Objects.requireNonNull(tenantDisplayName, "Tenant display name is required");
            Objects.requireNonNull(membershipId, "Tenant membership ID is required");
            Objects.requireNonNull(role, "Tenant role is required");
            Objects.requireNonNull(invitedAt, "Invitation timestamp is required");
        }
    }
}
