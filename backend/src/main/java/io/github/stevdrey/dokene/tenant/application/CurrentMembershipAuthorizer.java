// ABOUTME: Authorizes a privileged command against the membership as it is now, not as the request resolved it:
// ABOUTME: re-reads the member, requires the same membership, evaluates the permission and audits any denial.
package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import java.time.Clock;
import java.util.Objects;
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
        TenantContext member;
        try {
            member = memberships.resolve(request.identityId(), request.tenantId());
        } catch (TenantContextAuthorizationException exception) {
            throw denied(request, permission, resource, MEMBERSHIP_GONE);
        }
        if (!member.membershipId().equals(request.membershipId())) {
            throw denied(request, permission, resource, MEMBERSHIP_GONE);
        }
        AuthorizationDecision decision = authorization.evaluate(member, permission, resource);
        if (!decision.isAllowed()) {
            throw denied(request, permission, resource, decision.rejectionReason().orElse("Denied"));
        }
        return member;
    }

    private TenantAccessDeniedException denied(TenantContext request, TenantPermission permission,
            TenantScopedResource resource, String reason) {
        audits.onAuthorizationDenied(AuthorizationDeniedEvent.of(request, permission,
                resource == null ? null : resource.tenantId(), reason, clock.instant()));
        return new TenantAccessDeniedException();
    }
}
