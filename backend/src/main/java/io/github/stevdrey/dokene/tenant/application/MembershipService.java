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
import org.springframework.dao.DataIntegrityViolationException;
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
    private final MembershipChangeAuthorizer changeAuthority;
    private final MembershipAuditPort auditPort;
    private final Clock clock;

    public MembershipService(
            TenantAuthorizationService authorization,
            TenantContextProvider contexts,
            TenantMembershipRepository memberships,
            MembershipRoleService membershipRoleService,
            MembershipChangeAuthorizer changeAuthority,
            MembershipAuditPort auditPort,
            Clock clock
    ) {
        this.authorization = Objects.requireNonNull(authorization, "Authorization service is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
        this.memberships = Objects.requireNonNull(memberships, "Membership repository is required");
        this.membershipRoleService = Objects.requireNonNull(membershipRoleService, "Membership role service is required");
        this.changeAuthority = Objects.requireNonNull(changeAuthority, "Membership change authorizer is required");
        this.auditPort = Objects.requireNonNull(auditPort, "Membership audit port is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional(readOnly = true)
    public List<TenantMembership> listMemberships() {
        authorization.requirePermission(TenantPermission.MEMBERSHIP_READ);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        authorization.requireResourceAccess(TenantPermission.MEMBERSHIP_READ, tenantId);
        return memberships.findAllByTenantId(tenantId);
    }

    public TenantMembership addMembership(IdentityId targetIdentity, TenantRole role) {
        Objects.requireNonNull(targetIdentity, "Target identity is required");
        Objects.requireNonNull(role, "Role is required");

        return changeAuthority.change(TenantPermission.MEMBERSHIP_INVITE, targetIdentity, () -> {
            if (role == TenantRole.OWNER) {
                throw new IllegalArgumentException("Ownership cannot be assigned via membership invitation");
            }

            TenantId tenantId = contexts.requireCurrent().tenantId();
            Optional<TenantMembership> existing = memberships.findByTenantIdAndIdentityId(tenantId, targetIdentity);
            if (existing.isPresent()) {
                throw new IllegalStateException("Membership already exists for identity %s in tenant %s"
                        .formatted(targetIdentity.value(), tenantId.value()));
            }

            // The invitee gains nothing until they accept (issue 317, audit L2).
            TenantMembership membership = TenantMembership.invite(
                    TenantMembershipId.random(),
                    tenantId,
                    targetIdentity,
                    role,
                    clock.instant()
            );
            TenantMembership saved;
            try {
                saved = memberships.save(membership);
            } catch (DataIntegrityViolationException exception) {
                throw new IllegalStateException("Membership already exists for identity %s in tenant %s"
                        .formatted(targetIdentity.value(), tenantId.value()), exception);
            }
            auditPort.membershipCreated(saved.id(), role);
            return saved;
        });
    }

    /**
     * Returns a revoked membership to {@code INVITED}, keeping its id and role. The invitee must accept it again
     * before it grants access, so a reactivation never re-enters anyone into a workspace on its own. Requires the
     * same permission as an invitation; only {@code REVOKED} memberships qualify, and ownership never does.
     */
    public TenantMembership reactivateMembership(IdentityId targetIdentity) {
        Objects.requireNonNull(targetIdentity, "Target identity is required");
        return changeAuthority.change(TenantPermission.MEMBERSHIP_INVITE, targetIdentity, () -> {
            TenantMembership membership = memberships
                    .findByTenantIdAndIdentityId(contexts.requireCurrent().tenantId(), targetIdentity)
                    .orElseThrow(MembershipNotFoundException::new);
            authorization.requireResourceAccess(TenantPermission.MEMBERSHIP_INVITE, membership.tenantId());

            membership.reactivate(clock.instant());
            TenantMembership saved = memberships.save(membership);
            auditPort.membershipReactivated(saved.id(), saved.role());
            return saved;
        });
    }

    public void changeRole(IdentityId targetIdentity, TenantRole newRole) {
        membershipRoleService.changeRole(targetIdentity, newRole);
    }

    public void revokeMembership(IdentityId targetIdentity) {
        Objects.requireNonNull(targetIdentity, "Target identity is required");
        changeAuthority.change(TenantPermission.MEMBERSHIP_REVOKE, targetIdentity, () -> {
            TenantMembership membership = memberships
                    .findByTenantIdAndIdentityId(contexts.requireCurrent().tenantId(), targetIdentity)
                    .orElseThrow(MembershipNotFoundException::new);
            authorization.requireResourceAccess(TenantPermission.MEMBERSHIP_REVOKE, membership.tenantId());

            if (membership.role() == TenantRole.OWNER) {
                throw new IllegalArgumentException("Owner membership cannot be revoked");
            }

            membership.revoke(clock.instant());
            memberships.save(membership);
            auditPort.membershipRevoked(membership.id());
        });
    }
}
