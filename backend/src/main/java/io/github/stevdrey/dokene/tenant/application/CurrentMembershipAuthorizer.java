// ABOUTME: Authorizes a privileged command against the membership as it is now, not as the request resolved it:
// ABOUTME: re-reads the member, requires the same membership, evaluates the permission and audits any denial.
package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link TenantAuthorizationService} trusts the role and status cached in the request's {@link TenantContext}. A
 * command with a side effect that a revocation or demotion must still stop (an approval, a send) calls this inside its
 * transaction, typically under the row lock that serializes it, so a membership change committed before that point
 * refuses the command. A member who was removed and re-invited holds a new membership and does not count as the one
 * who made the request. Denials are audited through {@link AuthorizationAuditListener} like every other denial, with
 * one of its closed reason phrases.
 */
@Component
public class CurrentMembershipAuthorizer {
    /** The denial phrase the authorization audit maps to an inactive membership. */
    static final String MEMBERSHIP_GONE = "Tenant membership not found";

    private final TenantContextProvider contexts;
    private final TenantContextResolver memberships;
    private final TenantAuthorizationService authorization;
    private final AuthorizationAuditListener audits;
    private final Clock clock;

    public CurrentMembershipAuthorizer(TenantContextProvider contexts, TenantContextResolver memberships,
            TenantAuthorizationService authorization, AuthorizationAuditListener audits, Clock clock) {
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
        this.memberships = Objects.requireNonNull(memberships, "Tenant context resolver is required");
        this.authorization = Objects.requireNonNull(authorization, "Authorization service is required");
        this.audits = Objects.requireNonNull(audits, "Authorization audit listener is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /**
     * The current member's fresh context, allowed {@code permission} on {@code resource}; otherwise the denial is
     * audited and {@link TenantAccessDeniedException} is thrown.
     */
    public TenantContext requireResourceAccess(TenantPermission permission, TenantScopedResource resource) {
        Objects.requireNonNull(permission, "Permission is required");
        TenantContext request = contexts.current().orElse(null);
        if (request == null) {
            throw denied(null, permission, resource, "No active tenant context");
        }
        TenantContext member = sameMembership(request.identityId(), request.tenantId(), request.membershipId())
                .orElseThrow(() -> denied(request, permission, resource, MEMBERSHIP_GONE));
        AuthorizationDecision decision = authorization.evaluate(member, permission, resource);
        if (!decision.isAllowed()) {
            throw denied(request, permission, resource, decision.rejectionReason().orElse("Denied"));
        }
        return member;
    }

    /**
     * The member's fresh context while {@code membershipId} is still its active membership in {@code tenantId} and
     * its role holds {@code permission}; empty otherwise. Nothing is audited: this is for a background check that
     * repeats on every run (dispatch recovery) and is followed by {@link #requireResourceAccess} in the transaction.
     */
    public Optional<TenantContext> currentMember(IdentityId identityId, TenantId tenantId,
            TenantMembershipId membershipId, TenantPermission permission) {
        Objects.requireNonNull(permission, "Permission is required");
        return sameMembership(identityId, tenantId, membershipId)
                .filter(member -> authorization.evaluate(member, permission, tenantId).isAllowed());
    }

    /** A removed and re-invited member holds a new membership and does not count as the expected one. */
    private Optional<TenantContext> sameMembership(IdentityId identityId, TenantId tenantId,
            TenantMembershipId expected) {
        TenantContext member;
        try {
            member = memberships.resolve(identityId, tenantId);
        } catch (TenantContextAuthorizationException exception) {
            return Optional.empty();
        }
        return member.membershipId().equals(expected) ? Optional.of(member) : Optional.empty();
    }

    private TenantAccessDeniedException denied(TenantContext request, TenantPermission permission,
            TenantScopedResource resource, String reason) {
        audits.onAuthorizationDenied(AuthorizationDeniedEvent.of(request, permission,
                resource == null ? null : resource.tenantId(), reason, clock.instant()));
        return new TenantAccessDeniedException();
    }
}
