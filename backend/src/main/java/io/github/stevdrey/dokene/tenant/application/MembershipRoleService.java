package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal use case; ownership transfer is deliberately excluded. */
@Service
public class MembershipRoleService {
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final TenantMembershipRepository memberships;
    private final MembershipAuditPort audit;
    private final Clock clock;

    public MembershipRoleService(TenantAuthorizationService authorization, TenantContextProvider contexts,
            TenantMembershipRepository memberships, MembershipAuditPort audit, Clock clock) {
        this.authorization = authorization;
        this.contexts = contexts;
        this.memberships = memberships;
        this.audit = Objects.requireNonNull(audit, "Membership audit port is required");
        this.clock = clock;
    }

    @Transactional
    public void changeRole(IdentityId targetIdentity, TenantRole newRole) {
        authorization.requirePermission(TenantPermission.MEMBERSHIP_ROLE_UPDATE);
        Objects.requireNonNull(targetIdentity, "Target identity is required");
        Objects.requireNonNull(newRole, "New role is required");
        TenantMembership membership = memberships.findByTenantIdAndIdentityId(
                contexts.requireCurrent().tenantId(), targetIdentity)
                .orElseThrow(() -> new IllegalArgumentException("Membership is unavailable"));
        authorization.requireResourceAccess(TenantPermission.MEMBERSHIP_ROLE_UPDATE, membership.tenantId());
        TenantRole previousRole = membership.role();
        if (previousRole == TenantRole.OWNER || newRole == TenantRole.OWNER) {
            throw new IllegalArgumentException("Ownership changes require a separate use case");
        }
        membership.changeRole(newRole, clock.instant());
        memberships.save(membership);
        audit.roleChanged(membership.id(), previousRole, newRole);
    }
}
