// ABOUTME: Re-authorizes the actor of a membership change inside its transaction, after row-locking the actor's and the
// ABOUTME: target's memberships, so a concurrent role change or revocation of the actor cannot be bypassed (issue 325).
package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The request's {@link TenantContext} carries the role resolved when the request started. A membership change (an
 * invitation, a reactivation, a role change, a revocation) must instead be decided against the actor's membership as
 * it is when the change is written. Every membership change locks the actor's and the target's rows through
 * {@link TenantMembershipRepository#lockForMembershipChange} first, so:
 *
 * <ul>
 *   <li>a change that demotes or revokes the actor and commits first makes this one wait, then fail the fresh check;</li>
 *   <li>a change that starts later waits for this one, which was authorized against the then-current membership.</li>
 * </ul>
 *
 * <p>The lock is scoped to the request's tenant (and RLS), and refusals are audited by
 * {@link CurrentMembershipAuthorizer}. Must run inside the transaction that writes the change.</p>
 */
@Component
public class MembershipChangeAuthorizer {
    private final TenantContextProvider contexts;
    private final TenantMembershipRepository memberships;
    private final CurrentMembershipAuthorizer currentMembers;

    public MembershipChangeAuthorizer(TenantContextProvider contexts, TenantMembershipRepository memberships,
            CurrentMembershipAuthorizer currentMembers) {
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
        this.memberships = Objects.requireNonNull(memberships, "Membership repository is required");
        this.currentMembers = Objects.requireNonNull(currentMembers, "Current membership authorizer is required");
    }

    /**
     * Locks the actor's and {@code target}'s memberships in the current tenant, then returns the actor's fresh context
     * if its same, still active membership holds {@code permission}; otherwise the refusal is audited and
     * {@link TenantAccessDeniedException} is thrown.
     */
    public TenantContext requireCurrentAuthority(TenantPermission permission, IdentityId target) {
        Objects.requireNonNull(permission, "Permission is required");
        Objects.requireNonNull(target, "Target identity is required");
        TenantContext request = contexts.requireCurrent();
        memberships.lockForMembershipChange(request.tenantId(), List.of(request.identityId(), target));
        return currentMembers.requireResourceAccess(permission, request::tenantId);
    }
}
