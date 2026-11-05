// ABOUTME: The invitee's side of a membership invitation (issue 317, audit L2): list pending invitations and accept
// ABOUTME: one, which activates the membership and audits the acceptance in the same transaction.
package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.application.TenantMembershipDiscovery.ActiveTenantMembership;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs on the global {@code /api/tenants} boundary, where no tenant context exists: only the authenticated identity
 * itself can see or accept its invitations, and an invitation becomes a tenant context only once accepted.
 */
@Service
public class MembershipInvitationService {

    private final TenantInvitationDirectory invitations;
    private final TenantContextProvider contexts;
    private final MembershipAuditPort auditPort;

    public MembershipInvitationService(
            TenantInvitationDirectory invitations,
            TenantContextProvider contexts,
            MembershipAuditPort auditPort
    ) {
        this.invitations = Objects.requireNonNull(invitations, "Invitation directory is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
        this.auditPort = Objects.requireNonNull(auditPort, "Membership audit port is required");
    }

    @Transactional(readOnly = true)
    public List<TenantInvitationDirectory.PendingInvitation> listPendingInvitations(IdentityId identityId) {
        Objects.requireNonNull(identityId, "Identity is required");
        requireNoTenantContext();
        return invitations.findPendingInvitations(identityId);
    }

    /**
     * Accepts the identity's pending invitation to {@code tenantId}. The acceptance is audited as the invitee, under
     * the membership it just activated; if the audit fails the acceptance rolls back.
     *
     * @throws MembershipNotFoundException when there is no pending invitation to accept
     */
    @Transactional
    public ActiveTenantMembership acceptInvitation(IdentityId identityId, TenantId tenantId) {
        Objects.requireNonNull(identityId, "Identity is required");
        Objects.requireNonNull(tenantId, "Tenant is required");
        requireNoTenantContext();
        ActiveTenantMembership accepted = invitations.accept(identityId, tenantId)
                .orElseThrow(MembershipNotFoundException::new);
        TenantContext member = new TenantContext(accepted.tenantId(), identityId, accepted.membershipId(),
                accepted.role(), TenantMembershipStatus.ACTIVE);
        contexts.runWithContext(member, () -> auditPort.invitationAccepted(accepted.membershipId(), accepted.role()));
        return accepted;
    }

    private void requireNoTenantContext() {
        if (contexts.current().isPresent()) {
            throw new IllegalStateException("Invitations are handled outside any tenant context");
        }
    }
}
