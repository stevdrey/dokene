package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Internal use case; ownership transfer is deliberately excluded. */
@Service
public class MembershipRoleService {
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final TenantMembershipRepository memberships;
    private final MembershipChangeAuthorizer changeAuthority;
    private final MembershipAuditPort audit;
    private final Clock clock;

    public MembershipRoleService(TenantAuthorizationService authorization, TenantContextProvider contexts,
            TenantMembershipRepository memberships, MembershipChangeAuthorizer changeAuthority,
            MembershipAuditPort audit, Clock clock) {
        this.authorization = authorization;
        this.changeAuthority = Objects.requireNonNull(changeAuthority, "Membership change authorizer is required");
        this.contexts = contexts;
        this.memberships = memberships;
        this.audit = Objects.requireNonNull(audit, "Membership audit port is required");
        this.clock = clock;
    }

    public void changeRole(IdentityId targetIdentity, TenantRole newRole) {
        changeAuthority.change(TenantPermission.MEMBERSHIP_ROLE_UPDATE, targetIdentity, () -> {
            Objects.requireNonNull(newRole, "New role is required");
            TenantMembership membership = memberships.findByTenantIdAndIdentityId(
                    contexts.requireCurrent().tenantId(), targetIdentity)
                    .orElseThrow(MembershipNotFoundException::new);
            authorization.requireResourceAccess(TenantPermission.MEMBERSHIP_ROLE_UPDATE, membership.tenantId());
            TenantRole previousRole = membership.role();
            if (previousRole == TenantRole.OWNER || newRole == TenantRole.OWNER) {
                throw new IllegalArgumentException("Ownership changes require a separate use case");
            }
            membership.changeRole(newRole, clock.instant());
            memberships.save(membership);
            audit.roleChanged(membership.id(), previousRole, newRole);
        });
    }
}
