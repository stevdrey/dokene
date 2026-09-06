package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRecord;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Narrowly scoped application service for atomic workspace provisioning.
 */
@Service
public class WorkspaceProvisioningService {

    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = WorkspaceProvisioningRecord.IDEMPOTENCY_KEY_MAX_LENGTH;

    private final TenantRepository tenantRepository;
    private final TenantMembershipRepository tenantMembershipRepository;
    private final WorkspaceProvisioningRepository workspaceProvisioningRepository;
    private final ProvisioningAuthorizationPolicy provisioningAuthorizationPolicy;
    private final TenantContextProvider tenantContextProvider;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public WorkspaceProvisioningService(
            TenantRepository tenantRepository,
            TenantMembershipRepository tenantMembershipRepository,
            WorkspaceProvisioningRepository workspaceProvisioningRepository,
            ProvisioningAuthorizationPolicy provisioningAuthorizationPolicy,
            TenantContextProvider tenantContextProvider,
            AuditRecorder auditRecorder,
            Clock clock
    ) {
        this.tenantRepository = Objects.requireNonNull(tenantRepository, "Tenant repository is required");
        this.tenantMembershipRepository = Objects.requireNonNull(tenantMembershipRepository, "Tenant membership repository is required");
        this.workspaceProvisioningRepository = Objects.requireNonNull(workspaceProvisioningRepository, "Workspace provisioning repository is required");
        this.provisioningAuthorizationPolicy = Objects.requireNonNull(provisioningAuthorizationPolicy, "Provisioning authorization policy is required");
        this.tenantContextProvider = Objects.requireNonNull(tenantContextProvider, "Tenant context provider is required");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "Audit recorder is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional
    public ProvisionedWorkspace provisionWorkspace(IdentityId identityId, String idempotencyKey, String displayName) {
        Objects.requireNonNull(identityId, "Identity ID is required");
        String normalizedKey = WorkspaceProvisioningRecord.normalizeIdempotencyKey(idempotencyKey);
        String normalizedDisplayName = Tenant.normalizeDisplayName(displayName);

        if (!provisioningAuthorizationPolicy.isAllowed(identityId)) {
            auditRecorder.authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
            throw new TenantAccessDeniedException("Workspace provisioning is not permitted for this identity");
        }

        workspaceProvisioningRepository.acquireIdempotencyLock(identityId, normalizedKey);

        Optional<WorkspaceProvisioningRecord> existingRecord =
                workspaceProvisioningRepository.findByIdentityIdAndIdempotencyKey(identityId, normalizedKey);
        if (existingRecord.isPresent()) {
            WorkspaceProvisioningRecord record = existingRecord.get();
            Tenant existingTenant = tenantRepository.findById(record.tenantId())
                    .orElseThrow(() -> new IllegalStateException("Provisioned tenant not found"));
            if (!existingTenant.displayName().equals(normalizedDisplayName)) {
                throw new IdempotencyConflictException(
                        "Idempotency key '%s' was already used with a different workspace name".formatted(normalizedKey)
                );
            }
            TenantMembership membership = tenantContextProvider.callWithTenantId(record.tenantId(), () ->
                    tenantMembershipRepository.findByTenantIdAndIdentityId(record.tenantId(), identityId)
                            .orElseThrow(() -> new IllegalStateException("Owner membership not found for provisioned tenant"))
            );
            return new ProvisionedWorkspace(record.tenantId(), existingTenant.displayName(), membership.id(), membership.role(), false);
        }

        TenantId tenantId = TenantId.random();
        Instant now = clock.instant();
        Tenant tenant = Tenant.create(tenantId, normalizedDisplayName, now);
        TenantMembershipId membershipId = TenantMembershipId.random();
        TenantMembership ownerMembership = TenantMembership.createActive(
                membershipId, tenantId, identityId, TenantRole.OWNER, now
        );
        WorkspaceProvisioningRecord record = new WorkspaceProvisioningRecord(
                UUID.randomUUID(), normalizedKey, identityId, tenantId, normalizedDisplayName, now
        );

        tenantRepository.save(tenant);
        tenantContextProvider.callWithTenantId(tenantId, () -> {
            tenantMembershipRepository.save(ownerMembership);
            return null;
        });
        workspaceProvisioningRepository.save(record);
        return new ProvisionedWorkspace(tenantId, tenant.displayName(), membershipId, TenantRole.OWNER, true);
    }

    public record ProvisionedWorkspace(
            TenantId tenantId,
            String displayName,
            TenantMembershipId membershipId,
            TenantRole role,
            boolean created
    ) {
        public ProvisionedWorkspace {
            Objects.requireNonNull(tenantId, "Tenant ID is required");
            Objects.requireNonNull(displayName, "Display name is required");
            Objects.requireNonNull(membershipId, "Membership ID is required");
            Objects.requireNonNull(role, "Role is required");
        }
    }
}
