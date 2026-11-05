// ABOUTME: The invitee's side of a membership invitation (issue 317, audit L2): list pending invitations and accept
// ABOUTME: one, which activates the membership and audits the acceptance in the same transaction.
package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.application.TenantMembershipDiscovery.ActiveTenantMembership;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
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
    private final TenantContextResolver resolver;
    private final MembershipAuditPort auditPort;
    private final Clock clock;

    public MembershipInvitationService(
            TenantInvitationDirectory invitations,
            TenantContextProvider contexts,
            TenantContextResolver resolver,
            MembershipAuditPort auditPort,
            Clock clock
    ) {
        this.invitations = Objects.requireNonNull(invitations, "Invitation directory is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
        this.resolver = Objects.requireNonNull(resolver, "Tenant context resolver is required");
        this.auditPort = Objects.requireNonNull(auditPort, "Membership audit port is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional(readOnly = true)
    public List<TenantInvitationDirectory.PendingInvitation> listPendingInvitations(IdentityId identityId) {
        Objects.requireNonNull(identityId, "Identity is required");
        requireNoTenantContext();
        return invitations.findPendingInvitations(identityId);
    }

    /**
     * Accepts the identity's pending invitation to {@code tenantId}. The acceptance is audited as the invitee, under
     * the context the resolver now builds for the membership it just activated; if either fails, it rolls back.
     *
     * @throws MembershipNotFoundException when there is no pending invitation to accept
     */
    @Transactional
    public ActiveTenantMembership acceptInvitation(IdentityId identityId, TenantId tenantId) {
        Objects.requireNonNull(identityId, "Identity is required");
        Objects.requireNonNull(tenantId, "Tenant is required");
        requireNoTenantContext();
        ActiveTenantMembership accepted = invitations.accept(identityId, tenantId, clock.instant())
                .orElseThrow(MembershipNotFoundException::new);
        // The same path every request takes, in this transaction, so it sees the membership the acceptance activated.
        TenantContext member = resolver.resolve(identityId, accepted.tenantId());
        if (!member.membershipId().equals(accepted.membershipId())) {
            throw new IllegalStateException("Accepted membership does not resolve to the caller's tenant context");
        }
        contexts.runWithContext(member, () -> auditPort.invitationAccepted(member.membershipId(), member.role()));
        return accepted;
    }

    private void requireNoTenantContext() {
        if (contexts.current().isPresent()) {
            throw new IllegalStateException("Invitations are handled outside any tenant context");
        }
    }
}
