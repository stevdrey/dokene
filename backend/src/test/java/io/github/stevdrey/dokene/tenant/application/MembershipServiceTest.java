package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

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

    private final List<List<IdentityId>> locks = new ArrayList<>();
    private final TenantMembershipRepository repository = new TenantMembershipRepository() {
        @Override
        public void lockForMembershipChange(TenantId tenantId, Collection<IdentityId> identityIds) {
            assertThat(tenantId).isEqualTo(MembershipServiceTest.this.tenantId);
            locks.add(List.copyOf(identityIds));
        }

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

    /** Resolves the actor's membership as stored now, like the database-backed resolver does. */
    private final TenantContextResolver resolver = (identityId, requestedTenantId) -> Optional
            .ofNullable(memberships.get(requestedTenantId.value() + ":" + identityId.value()))
            .filter(membership -> membership.status() == TenantMembershipStatus.ACTIVE)
            .map(membership -> new TenantContext(membership.tenantId(), identityId, membership.id(), membership.role()))
            .orElseThrow(TenantContextAuthorizationException::new);
    private final MembershipChangeAuthorizer changeAuthority = new MembershipChangeAuthorizer(contexts, repository,
            new CurrentMembershipAuthorizer(contexts, resolver, authorization, auditListener, clock));

    private final MembershipAuditPort auditPort = mock(MembershipAuditPort.class);
    private MembershipService service;
    private TenantContext ownerContext;

    @BeforeEach
    void setUp() {
        TenantMembership ownerMembership = TenantMembership.createActive(
                TenantMembershipId.random(), tenantId, ownerIdentity, TenantRole.OWNER, now
        );
        repository.save(ownerMembership);
        ownerContext = new TenantContext(tenantId, ownerIdentity, ownerMembership.id(), TenantRole.OWNER, TenantMembershipStatus.ACTIVE);

        MembershipRoleService roleService = new MembershipRoleService(authorization, contexts, repository,
                changeAuthority, auditPort, clock);
        service = new MembershipService(authorization, contexts, repository, roleService, changeAuthority, auditPort,
                clock);
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
    void addMembershipCreatesAnInvitationThatGrantsNothingUntilAccepted() {
        contexts.runWithContext(ownerContext, () -> {
            TenantMembership created = service.addMembership(operatorIdentity, TenantRole.OPERATOR);
            assertThat(created.role()).isEqualTo(TenantRole.OPERATOR);
            assertThat(created.status()).isEqualTo(TenantMembershipStatus.INVITED);
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

    @Test
    void cannotRevokeAbsentMember() {
        contexts.runWithContext(ownerContext, () -> {
            assertThatThrownBy(() -> service.revokeMembership(new IdentityId(UUID.randomUUID())))
                    .isInstanceOf(MembershipNotFoundException.class)
                    .hasMessage("Membership is unavailable");
        });
    }

    @Test
    void addMembershipInvokesAuditPort() {
        contexts.runWithContext(ownerContext, () -> {
            TenantMembership created = service.addMembership(operatorIdentity, TenantRole.OPERATOR);
            verify(auditPort).membershipCreated(created.id(), TenantRole.OPERATOR);
        });
    }

    @Test
    void revokeMembershipInvokesAuditPort() {
        contexts.runWithContext(ownerContext, () -> {
            TenantMembership created = service.addMembership(operatorIdentity, TenantRole.OPERATOR);
            service.revokeMembership(operatorIdentity);
            verify(auditPort).membershipRevoked(created.id());
        });
    }

    @Test
    void unauthorizedCallerInvitingOwnerFailsWithForbiddenNotBadRequest() {
        TenantMembership opMembership = repository.findByTenantIdAndIdentityId(tenantId, operatorIdentity).orElseGet(() -> {
            TenantMembership m = TenantMembership.createActive(TenantMembershipId.random(), tenantId, operatorIdentity, TenantRole.OPERATOR, now);
            return repository.save(m);
        });
        TenantContext opContext = new TenantContext(tenantId, operatorIdentity, opMembership.id(), TenantRole.OPERATOR, TenantMembershipStatus.ACTIVE);
        contexts.runWithContext(opContext, () -> {
            assertThatThrownBy(() -> service.addMembership(new IdentityId(UUID.randomUUID()), TenantRole.OWNER))
                    .isInstanceOf(TenantAccessDeniedException.class);
        });
    }

    @Test
    void reactivationReturnsARevokedMembershipToInvitedAndAuditsIt() {
        contexts.runWithContext(ownerContext, () -> {
            TenantMembership created = service.addMembership(operatorIdentity, TenantRole.OPERATOR);
            service.revokeMembership(operatorIdentity);

            TenantMembership reactivated = service.reactivateMembership(operatorIdentity);

            assertThat(reactivated.id()).isEqualTo(created.id());
            assertThat(reactivated.status()).isEqualTo(TenantMembershipStatus.INVITED);
            assertThat(reactivated.role()).isEqualTo(TenantRole.OPERATOR);
            verify(auditPort).membershipReactivated(created.id(), TenantRole.OPERATOR);
        });
    }

    @Test
    void reactivationRejectsMembershipsThatAreNotRevoked() {
        contexts.runWithContext(ownerContext, () -> {
            service.addMembership(operatorIdentity, TenantRole.OPERATOR);
            assertThatThrownBy(() -> service.reactivateMembership(operatorIdentity))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.reactivateMembership(ownerIdentity))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.reactivateMembership(new IdentityId(UUID.randomUUID())))
                    .isInstanceOf(MembershipNotFoundException.class);
        });
        org.mockito.Mockito.verify(auditPort, org.mockito.Mockito.never())
                .membershipReactivated(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reactivationRequiresTheInvitePermission() {
        contexts.runWithContext(ownerContext, () -> {
            service.addMembership(operatorIdentity, TenantRole.OPERATOR);
            service.revokeMembership(operatorIdentity);
        });
        IdentityId viewerIdentity = new IdentityId(UUID.randomUUID());
        TenantMembership viewer = repository.save(TenantMembership.createActive(
                TenantMembershipId.random(), tenantId, viewerIdentity, TenantRole.VIEWER, now));
        TenantContext viewerContext = new TenantContext(tenantId, viewerIdentity, viewer.id(), TenantRole.VIEWER,
                TenantMembershipStatus.ACTIVE);

        contexts.runWithContext(viewerContext, () -> assertThatThrownBy(
                () -> service.reactivateMembership(operatorIdentity)).isInstanceOf(TenantAccessDeniedException.class));
        assertThat(repository.findByTenantIdAndIdentityId(tenantId, operatorIdentity).orElseThrow().status())
                .isEqualTo(TenantMembershipStatus.REVOKED);
    }

    @Test
    void addMembershipHandlesConcurrentDuplicateException() {
        TenantMembershipRepository throwingRepo = mock(TenantMembershipRepository.class);
        org.mockito.Mockito.when(throwingRepo.findByTenantIdAndIdentityId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.when(throwingRepo.save(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        MembershipService concurrentService = new MembershipService(
                authorization, contexts, throwingRepo,
                new MembershipRoleService(authorization, contexts, throwingRepo, changeAuthority, auditPort, clock),
                changeAuthority, auditPort, clock);

        contexts.runWithContext(ownerContext, () -> {
            assertThatThrownBy(() -> concurrentService.addMembership(new IdentityId(UUID.randomUUID()), TenantRole.OPERATOR))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Membership already exists");
        });
    }

    @Test
    void everyMembershipChangeLocksTheActorAndTheTargetBeforeDeciding() {
        contexts.runWithContext(ownerContext, () -> {
            service.addMembership(operatorIdentity, TenantRole.OPERATOR);
            service.changeRole(operatorIdentity, TenantRole.VIEWER);
            service.revokeMembership(operatorIdentity);
            service.reactivateMembership(operatorIdentity);
        });

        assertThat(locks).hasSize(4)
                .allSatisfy(lock -> assertThat(lock).containsExactly(ownerIdentity, operatorIdentity));
    }

    @Test
    void anActorDemotedAfterTheRequestResolvedIsRefusedForEveryChangeAndAudited() {
        TenantMembership admin = seedActive(TenantRole.ADMIN);
        TenantContext staleAdmin = context(admin, TenantRole.ADMIN);
        IdentityId revokedIdentity = new IdentityId(UUID.randomUUID());
        contexts.runWithContext(ownerContext, () -> {
            service.addMembership(operatorIdentity, TenantRole.OPERATOR);
            service.addMembership(revokedIdentity, TenantRole.VIEWER);
            service.revokeMembership(revokedIdentity);
        });
        // A concurrent demotion committed after the actor's request resolved its ADMIN context.
        admin.changeRole(TenantRole.VIEWER, now);
        repository.save(admin);
        org.mockito.Mockito.clearInvocations(auditPort, auditListener);

        contexts.runWithContext(staleAdmin, () -> {
            assertDenied(() -> service.addMembership(new IdentityId(UUID.randomUUID()), TenantRole.VIEWER));
            assertDenied(() -> service.changeRole(operatorIdentity, TenantRole.VIEWER));
            assertDenied(() -> service.revokeMembership(operatorIdentity));
            assertDenied(() -> service.reactivateMembership(revokedIdentity));
        });

        verify(auditListener, org.mockito.Mockito.times(4)).onAuthorizationDenied(org.mockito.ArgumentMatchers.argThat(
                event -> admin.id().equals(event.membershipId()) && event.reason().contains("lacks permission")));
        org.mockito.Mockito.verifyNoInteractions(auditPort);
        TenantMembership operator = repository.findByTenantIdAndIdentityId(tenantId, operatorIdentity).orElseThrow();
        assertThat(operator.role()).isEqualTo(TenantRole.OPERATOR);
        assertThat(operator.status()).isEqualTo(TenantMembershipStatus.INVITED);
        assertThat(repository.findByTenantIdAndIdentityId(tenantId, revokedIdentity).orElseThrow().status())
                .isEqualTo(TenantMembershipStatus.REVOKED);
    }

    @Test
    void aRevokedActorIsRefusedEvenWhileItAdministersAnotherTenant() {
        TenantMembership admin = seedActive(TenantRole.ADMIN);
        TenantContext staleAdmin = context(admin, TenantRole.ADMIN);
        contexts.runWithContext(ownerContext, () -> service.addMembership(operatorIdentity, TenantRole.OPERATOR));
        // Same identity, still ADMIN elsewhere: authority never crosses tenants.
        repository.save(TenantMembership.createActive(TenantMembershipId.random(), TenantId.random(),
                admin.identityId(), TenantRole.ADMIN, now));
        admin.revoke(now);
        repository.save(admin);
        org.mockito.Mockito.clearInvocations(auditListener);

        contexts.runWithContext(staleAdmin, () -> assertDenied(() -> service.revokeMembership(operatorIdentity)));

        verify(auditListener).onAuthorizationDenied(org.mockito.ArgumentMatchers.argThat(
                event -> CurrentMembershipAuthorizer.MEMBERSHIP_GONE.equals(event.reason())
                        && tenantId.equals(event.tenantId())));
        assertThat(repository.findByTenantIdAndIdentityId(tenantId, operatorIdentity).orElseThrow().status())
                .isEqualTo(TenantMembershipStatus.INVITED);
    }

    @Test
    void aReplacedActorMembershipDoesNotCarryTheRequestsAuthority() {
        TenantMembership admin = seedActive(TenantRole.ADMIN);
        TenantContext staleAdmin = context(admin, TenantRole.ADMIN);
        contexts.runWithContext(ownerContext, () -> service.addMembership(operatorIdentity, TenantRole.OPERATOR));
        // Removed and re-admitted as a new membership after the request resolved the old one.
        repository.save(TenantMembership.createActive(TenantMembershipId.random(), tenantId, admin.identityId(),
                TenantRole.ADMIN, now));

        contexts.runWithContext(staleAdmin,
                () -> assertDenied(() -> service.changeRole(operatorIdentity, TenantRole.VIEWER)));

        assertThat(repository.findByTenantIdAndIdentityId(tenantId, operatorIdentity).orElseThrow().role())
                .isEqualTo(TenantRole.OPERATOR);
    }

    @Test
    void anActorPromotedAfterTheRequestResolvedIsStillFirstCheckedAgainstTheRequest() {
        TenantMembership viewer = seedActive(TenantRole.VIEWER);
        TenantContext staleViewer = context(viewer, TenantRole.VIEWER);
        viewer.changeRole(TenantRole.ADMIN, now);
        repository.save(viewer);

        contexts.runWithContext(staleViewer,
                () -> assertDenied(() -> service.addMembership(operatorIdentity, TenantRole.OPERATOR)));

        assertThat(locks).isEmpty();
        assertThat(repository.findByTenantIdAndIdentityId(tenantId, operatorIdentity)).isEmpty();
    }

    private TenantMembership seedActive(TenantRole role) {
        return repository.save(TenantMembership.createActive(TenantMembershipId.random(), tenantId,
                new IdentityId(UUID.randomUUID()), role, now));
    }

    private TenantContext context(TenantMembership membership, TenantRole resolvedRole) {
        return new TenantContext(tenantId, membership.identityId(), membership.id(), resolvedRole,
                TenantMembershipStatus.ACTIVE);
    }

    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable change) {
        assertThatThrownBy(change).isInstanceOf(TenantAccessDeniedException.class);
    }
}
