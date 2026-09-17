package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for tenant-scoped membership lifecycle management.
 */
@Service
public class MembershipService {

    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final TenantMembershipRepository memberships;
    private final MembershipRoleService membershipRoleService;
    private final Clock clock;

    public MembershipService(
            TenantAuthorizationService authorization,
            TenantContextProvider contexts,
            TenantMembershipRepository memberships,
            MembershipRoleService membershipRoleService,
            Clock clock
    ) {
        this.authorization = Objects.requireNonNull(authorization, "Authorization service is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
        this.memberships = Objects.requireNonNull(memberships, "Membership repository is required");
        this.membershipRoleService = Objects.requireNonNull(membershipRoleService, "Membership role service is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional(readOnly = true)
    public List<TenantMembership> listMemberships() {
        authorization.requirePermission(TenantPermission.MEMBERSHIP_READ);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        authorization.requireResourceAccess(TenantPermission.MEMBERSHIP_READ, tenantId);
        return memberships.findAllByTenantId(tenantId);
    }

    @Transactional
    public TenantMembership addMembership(IdentityId targetIdentity, TenantRole role) {
        Objects.requireNonNull(targetIdentity, "Target identity is required");
        Objects.requireNonNull(role, "Role is required");
        if (role == TenantRole.OWNER) {
            throw new IllegalArgumentException("Ownership cannot be assigned via membership invitation");
        }

        authorization.requirePermission(TenantPermission.MEMBERSHIP_INVITE);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        authorization.requireResourceAccess(TenantPermission.MEMBERSHIP_INVITE, tenantId);

        Optional<TenantMembership> existing = memberships.findByTenantIdAndIdentityId(tenantId, targetIdentity);
        if (existing.isPresent()) {
            throw new IllegalStateException("Membership already exists for identity %s in tenant %s"
                    .formatted(targetIdentity.value(), tenantId.value()));
        }

        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(),
                tenantId,
                targetIdentity,
                role,
                clock.instant()
        );
        return memberships.save(membership);
    }

    @Transactional
    public void changeRole(IdentityId targetIdentity, TenantRole newRole) {
        membershipRoleService.changeRole(targetIdentity, newRole);
    }

    @Transactional
    public void revokeMembership(IdentityId targetIdentity) {
        Objects.requireNonNull(targetIdentity, "Target identity is required");
        authorization.requirePermission(TenantPermission.MEMBERSHIP_REVOKE);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        authorization.requireResourceAccess(TenantPermission.MEMBERSHIP_REVOKE, tenantId);

        TenantMembership membership = memberships.findByTenantIdAndIdentityId(tenantId, targetIdentity)
                .orElseThrow(() -> new IllegalArgumentException("Membership is unavailable"));
        authorization.requireResourceAccess(TenantPermission.MEMBERSHIP_REVOKE, membership.tenantId());

        if (membership.role() == TenantRole.OWNER) {
            throw new IllegalArgumentException("Owner membership cannot be revoked");
        }

        membership.revoke(clock.instant());
        memberships.save(membership);
    }
}
