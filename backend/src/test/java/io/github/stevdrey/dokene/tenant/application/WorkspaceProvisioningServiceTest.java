package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService.ProvisionedWorkspace;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRecord;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class WorkspaceProvisioningServiceTest {

    private final Instant now = Instant.parse("2026-09-06T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final IdentityId identity = new IdentityId(UUID.randomUUID());

    private final Map<TenantId, Tenant> tenants = new HashMap<>();
    private final Map<String, TenantMembership> memberships = new HashMap<>();
    private final Map<String, WorkspaceProvisioningRecord> provisioningRecords = new HashMap<>();
    private final List<AuditDenialRecord> auditDenials = new ArrayList<>();

    private final TenantContextProvider tenantContextProvider = new ScopedValueTenantContextProvider();

    private final TenantRepository tenantRepository = new TenantRepository() {
        @Override
        public Optional<Tenant> findById(TenantId id) {
            return Optional.ofNullable(tenants.get(id));
        }

        @Override
        public Tenant save(Tenant tenant) {
            tenants.put(tenant.id(), tenant);
            return tenant;
        }
    };

    private final TenantMembershipRepository membershipRepository = new TenantMembershipRepository() {
        @Override
        public Optional<TenantMembership> findByTenantIdAndIdentityId(TenantId tenantId, IdentityId identityId) {
            return Optional.ofNullable(memberships.get(tenantId.value() + ":" + identityId.value()));
        }

        @Override
        public TenantMembership save(TenantMembership membership) {
            memberships.put(membership.tenantId().value() + ":" + membership.identityId().value(), membership);
            return membership;
        }
    };

    private final WorkspaceProvisioningRepository provisioningRepository = new WorkspaceProvisioningRepository() {
        @Override
        public void acquireIdempotencyLock(IdentityId identityId, String idempotencyKey) {
        }

        @Override
        public Optional<WorkspaceProvisioningRecord> findByIdentityIdAndIdempotencyKey(IdentityId identityId, String idempotencyKey) {
            return Optional.ofNullable(provisioningRecords.get(identityId.value() + ":" + idempotencyKey));
        }

        @Override
        public WorkspaceProvisioningRecord save(WorkspaceProvisioningRecord record) {
            String key = record.identityId().value() + ":" + record.idempotencyKey();
            if (provisioningRecords.containsKey(key)) {
                throw new DataIntegrityViolationException("Unique constraint violation");
            }
            provisioningRecords.put(key, record);
            return record;
        }
    };

    private final AuditRecorder auditRecorder = new AuditRecorder() {
        @Override
        public void authorizationDenied(TenantPermission permission, AuditDenialReason reason) {
            auditDenials.add(new AuditDenialRecord(permission, reason));
        }

        @Override
        public void membershipRoleChanged(TenantMembershipId target, TenantRole previousRole, TenantRole newRole) {
        }
    };

    private boolean provisioningAllowed = true;
    private final ProvisioningAuthorizationPolicy policy = id -> provisioningAllowed;

    private WorkspaceProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceProvisioningService(
                tenantRepository,
                membershipRepository,
                provisioningRepository,
                policy,
                tenantContextProvider,
                auditRecorder,
                clock
        );
    }

    @Test
    void provisionsWorkspaceAndInitialOwnerAtomically() {
        ProvisionedWorkspace result = service.provisionWorkspace(identity, "key-1", "My Workspace");

        assertThat(result.created()).isTrue();
        assertThat(result.displayName()).isEqualTo("My Workspace");
        assertThat(result.role()).isEqualTo(TenantRole.OWNER);

        Tenant tenant = tenants.get(result.tenantId());
        assertThat(tenant).isNotNull();
        assertThat(tenant.displayName()).isEqualTo("My Workspace");

        TenantMembership membership = memberships.get(result.tenantId().value() + ":" + identity.value());
        assertThat(membership).isNotNull();
        assertThat(membership.role()).isEqualTo(TenantRole.OWNER);
        assertThat(membership.status()).isEqualTo(TenantMembershipStatus.ACTIVE);

        WorkspaceProvisioningRecord record = provisioningRecords.get(identity.value() + ":key-1");
        assertThat(record).isNotNull();
        assertThat(record.tenantId()).isEqualTo(result.tenantId());
    }

    @Test
    void replayingWithSameKeyAndNameReturnsExistingWorkspaceWithoutCreatingDuplicates() {
        ProvisionedWorkspace first = service.provisionWorkspace(identity, "key-repeat", "Repeat Workspace");
        assertThat(first.created()).isTrue();

        ProvisionedWorkspace replay = service.provisionWorkspace(identity, "key-repeat", "Repeat Workspace");
        assertThat(replay.created()).isFalse();
        assertThat(replay.tenantId()).isEqualTo(first.tenantId());
        assertThat(replay.displayName()).isEqualTo("Repeat Workspace");
        assertThat(replay.membershipId()).isEqualTo(first.membershipId());
        assertThat(replay.role()).isEqualTo(TenantRole.OWNER);

        assertThat(tenants).hasSize(1);
        assertThat(memberships).hasSize(1);
        assertThat(provisioningRecords).hasSize(1);
    }

    @Test
    void replayingWithSameKeyAndDifferentNameThrowsIdempotencyConflict() {
        service.provisionWorkspace(identity, "key-conflict", "Original Name");

        assertThatThrownBy(() -> service.provisionWorkspace(identity, "key-conflict", "Different Name"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already used with a different workspace name");

        assertThat(tenants).hasSize(1);
    }

    @Test
    void replayingWithSameKeyAndUnicodeNormalizedNameSucceedsWithoutConflict() {
        ProvisionedWorkspace first = service.provisionWorkspace(identity, "key-unicode", "Acme Corp");
        assertThat(first.created()).isTrue();

        ProvisionedWorkspace replay = service.provisionWorkspace(identity, "key-unicode", "\u00A0Acme Corp \u00A0");
        assertThat(replay.created()).isFalse();
        assertThat(replay.tenantId()).isEqualTo(first.tenantId());
        assertThat(replay.displayName()).isEqualTo("Acme Corp");
    }

    @Test
    void deniesUnauthorizedIdentityAndRecordsAuditDenial() {
        provisioningAllowed = false;

        assertThatThrownBy(() -> service.provisionWorkspace(identity, "key-unauth", "Denied Workspace"))
                .isInstanceOf(TenantAccessDeniedException.class)
                .hasMessageContaining("Workspace provisioning is not permitted");

        assertThat(tenants).isEmpty();
        assertThat(memberships).isEmpty();
        assertThat(provisioningRecords).isEmpty();

        assertThat(auditDenials).containsExactly(
                new AuditDenialRecord(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT)
        );
    }

    @Test
    void validatesBlankOrNullInputs() {
        assertThatThrownBy(() -> service.provisionWorkspace(identity, null, "Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key is required");

        assertThatThrownBy(() -> service.provisionWorkspace(identity, "", "Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key cannot be blank");

        assertThatThrownBy(() -> service.provisionWorkspace(identity, "   ", "Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key cannot be blank");

        assertThatThrownBy(() -> service.provisionWorkspace(identity, "\u00A0\u00A0", "Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key cannot be blank");

        assertThatThrownBy(() -> service.provisionWorkspace(identity, "key\0null", "Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key cannot contain NUL characters");

        assertThatThrownBy(() -> service.provisionWorkspace(identity, "key\uD800", "Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key cannot contain unpaired surrogates");

        assertThatThrownBy(() -> service.provisionWorkspace(identity, "k".repeat(129), "Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key cannot exceed 128 characters");

        assertThatThrownBy(() -> service.provisionWorkspace(identity, "key", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tenant display name cannot be blank");

        assertThatThrownBy(() -> service.provisionWorkspace(identity, "key", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tenant display name is required");

        assertThatThrownBy(() -> service.provisionWorkspace(null, "key", "Name"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void replayingWithSameKeyWithUnicodeSpacesSucceedsWithoutConflict() {
        ProvisionedWorkspace first = service.provisionWorkspace(identity, "key-trim", "Acme Corp");
        assertThat(first.created()).isTrue();

        ProvisionedWorkspace replay = service.provisionWorkspace(identity, "\u00A0key-trim \u00A0", "Acme Corp");
        assertThat(replay.created()).isFalse();
        assertThat(replay.tenantId()).isEqualTo(first.tenantId());
    }

    @Test
    void acquiresIdempotencyLockBeforeCheckingForAnExistingProvisioning() {
        List<String> calls = new ArrayList<>();
        WorkspaceProvisioningRepository lockingRepository = new WorkspaceProvisioningRepository() {
            @Override
            public void acquireIdempotencyLock(IdentityId identityId, String idempotencyKey) {
                calls.add("lock");
            }

            @Override
            public Optional<WorkspaceProvisioningRecord> findByIdentityIdAndIdempotencyKey(IdentityId identityId, String idempotencyKey) {
                calls.add("find");
                return Optional.empty();
            }

            @Override
            public WorkspaceProvisioningRecord save(WorkspaceProvisioningRecord record) {
                calls.add("save");
                return record;
            }
        };

        WorkspaceProvisioningService lockingService = new WorkspaceProvisioningService(
                tenantRepository, membershipRepository, lockingRepository, policy, tenantContextProvider, auditRecorder, clock
        );

        lockingService.provisionWorkspace(identity, "race-key", "Race Workspace");

        assertThat(calls).containsExactly("lock", "find", "save");
    }

    private record AuditDenialRecord(TenantPermission permission, AuditDenialReason reason) {
    }
}
