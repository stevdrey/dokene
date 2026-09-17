package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MembershipServiceTest {

    private final Instant now = Instant.parse("2026-09-17T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final TenantId tenantId = TenantId.random();
    private final IdentityId ownerIdentity = new IdentityId(UUID.randomUUID());
    private final IdentityId operatorIdentity = new IdentityId(UUID.randomUUID());
    private final Map<String, TenantMembership> memberships = new HashMap<>();

    private final TenantContextProvider contexts = new ScopedValueTenantContextProvider();
    private final AuthorizationAuditListener auditListener = mock(AuthorizationAuditListener.class);
    private final TenantAuthorizationService authorization = new DefaultTenantAuthorizationService(contexts, auditListener, clock);

    private final TenantMembershipRepository repository = new TenantMembershipRepository() {
        @Override
        public Optional<TenantMembership> findByTenantIdAndIdentityId(TenantId tenantId, IdentityId identityId) {
            return Optional.ofNullable(memberships.get(tenantId.value() + ":" + identityId.value()));
        }

        @Override
        public List<TenantMembership> findAllByTenantId(TenantId tenantId) {
            return memberships.values().stream()
                    .filter(m -> m.tenantId().equals(tenantId))
                    .toList();
        }

        @Override
        public TenantMembership save(TenantMembership membership) {
            memberships.put(membership.tenantId().value() + ":" + membership.identityId().value(), membership);
            return membership;
        }
    };

    private final MembershipAuditPort auditPort = (membershipId, prevRole, newRole) -> { };
    private MembershipService service;
    private TenantContext ownerContext;

    @BeforeEach
    void setUp() {
        TenantMembership ownerMembership = TenantMembership.createActive(
                TenantMembershipId.random(), tenantId, ownerIdentity, TenantRole.OWNER, now
        );
        repository.save(ownerMembership);
        ownerContext = new TenantContext(tenantId, ownerIdentity, ownerMembership.id(), TenantRole.OWNER, TenantMembershipStatus.ACTIVE);

        MembershipRoleService roleService = new MembershipRoleService(authorization, contexts, repository, auditPort, clock);
        service = new MembershipService(authorization, contexts, repository, roleService, clock);
    }

    @Test
    void listMembershipsReturnsAllMembershipsInContext() {
        contexts.runWithContext(ownerContext, () -> {
            List<TenantMembership> list = service.listMemberships();
            assertThat(list).hasSize(1);
            assertThat(list.getFirst().role()).isEqualTo(TenantRole.OWNER);
        });
    }

    @Test
    void addMembershipCreatesActiveMembership() {
        contexts.runWithContext(ownerContext, () -> {
            TenantMembership created = service.addMembership(operatorIdentity, TenantRole.OPERATOR);
            assertThat(created.role()).isEqualTo(TenantRole.OPERATOR);
            assertThat(created.status()).isEqualTo(TenantMembershipStatus.ACTIVE);
            assertThat(created.tenantId()).isEqualTo(tenantId);
            assertThat(created.identityId()).isEqualTo(operatorIdentity);
        });
    }

    @Test
    void cannotAddMemberWithOwnerRole() {
        contexts.runWithContext(ownerContext, () -> {
            assertThatThrownBy(() -> service.addMembership(operatorIdentity, TenantRole.OWNER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Ownership cannot be assigned via membership invitation");
        });
    }

    @Test
    void cannotAddExistingMember() {
        contexts.runWithContext(ownerContext, () -> {
            service.addMembership(operatorIdentity, TenantRole.OPERATOR);
            assertThatThrownBy(() -> service.addMembership(operatorIdentity, TenantRole.OPERATOR))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Membership already exists");
        });
    }

    @Test
    void revokeMembershipSetsStatusToRevoked() {
        contexts.runWithContext(ownerContext, () -> {
            service.addMembership(operatorIdentity, TenantRole.OPERATOR);
            service.revokeMembership(operatorIdentity);

            TenantMembership revoked = repository.findByTenantIdAndIdentityId(tenantId, operatorIdentity).orElseThrow();
            assertThat(revoked.status()).isEqualTo(TenantMembershipStatus.REVOKED);
        });
    }

    @Test
    void cannotRevokeOwner() {
        contexts.runWithContext(ownerContext, () -> {
            assertThatThrownBy(() -> service.revokeMembership(ownerIdentity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Owner membership cannot be revoked");
        });
    }
}
