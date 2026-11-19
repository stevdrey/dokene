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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;
    private final ProvisioningRefusalCounter refusalCounter;
    private final int maxWorkspacesPerIdentity;

    public WorkspaceProvisioningService(
            TenantRepository tenantRepository,
            TenantMembershipRepository tenantMembershipRepository,
            WorkspaceProvisioningRepository workspaceProvisioningRepository,
            ProvisioningAuthorizationPolicy provisioningAuthorizationPolicy,
            TenantContextProvider tenantContextProvider,
            AuditRecorder auditRecorder,
            Clock clock,
            PlatformTransactionManager transactionManager,
            ProvisioningRefusalCounter refusalCounter,
            ProvisioningProperties provisioningProperties
    ) {
        this.tenantRepository = Objects.requireNonNull(tenantRepository, "Tenant repository is required");
        this.tenantMembershipRepository = Objects.requireNonNull(tenantMembershipRepository, "Tenant membership repository is required");
        this.workspaceProvisioningRepository = Objects.requireNonNull(workspaceProvisioningRepository, "Workspace provisioning repository is required");
        this.provisioningAuthorizationPolicy = Objects.requireNonNull(provisioningAuthorizationPolicy, "Provisioning authorization policy is required");
        this.tenantContextProvider = Objects.requireNonNull(tenantContextProvider, "Tenant context provider is required");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "Audit recorder is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "Transaction manager is required"));
        // The cap count must see the rows the previous lock holder committed; under REPEATABLE READ the snapshot would
        // be taken at the lock statement, before the wait, and two requests could both read a count below the cap.
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.refusalCounter = Objects.requireNonNull(refusalCounter, "Refusal counter is required");
        this.maxWorkspacesPerIdentity = Objects.requireNonNull(provisioningProperties, "Provisioning properties are required")
                .maxWorkspacesPerIdentity();
    }

    /**
     * The policy check and its denial audit row run before the transaction opens (spec 0021): the audit recorder
     * writes on a second pooled connection, so a refusal must never hold a provisioning transaction while it does.
     * The cap needs the database, so it is checked inside the transaction under the per-identity lock; its refusal
     * is counted and audited only after that transaction has rolled back (ADR 0033 §6).
     */
    public ProvisionedWorkspace provisionWorkspace(IdentityId identityId, String idempotencyKey, String displayName) {
        Objects.requireNonNull(identityId, "Identity ID is required");
        String normalizedKey = WorkspaceProvisioningRecord.normalizeIdempotencyKey(idempotencyKey);
        String normalizedDisplayName = Tenant.normalizeDisplayName(displayName);

        if (!provisioningAuthorizationPolicy.isAllowed(identityId)) {
            refuse(identityId, ProvisioningRefusalCounter.Reason.POLICY);
            throw new ProvisioningNotPermittedException();
        }

        try {
            return transactionTemplate.execute(
                    status -> provisionInTransaction(identityId, normalizedKey, normalizedDisplayName));
        } catch (WorkspaceQuotaExceededException refusal) {
            // The template has rolled the transaction back and returned its connection by now (ADR 0033 §2, §6).
            refuse(identityId, ProvisioningRefusalCounter.Reason.QUOTA);
            throw refusal;
        }
    }

    /** Counts first, so a refusal whose audit row is suppressed or fails is still counted. */
    private void refuse(IdentityId identityId, ProvisioningRefusalCounter.Reason reason) {
        refusalCounter.refused(reason);
        auditRecorder.authorizationDenied(
                TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT, identityId);
    }

    private ProvisionedWorkspace provisionInTransaction(
            IdentityId identityId, String normalizedKey, String normalizedDisplayName
    ) {
        workspaceProvisioningRepository.acquireProvisioningLock(identityId, normalizedKey);

        Optional<WorkspaceProvisioningRecord> existingRecord =
                workspaceProvisioningRepository.findByIdentityIdAndIdempotencyKey(identityId, normalizedKey);
        if (existingRecord.isPresent()) {
            WorkspaceProvisioningRecord record = existingRecord.get();
            return tenantContextProvider.callWithTenantId(record.tenantId(), () -> {
                Tenant existingTenant = tenantRepository.findById(record.tenantId())
                        .orElseThrow(() -> new IllegalStateException("Provisioned tenant not found"));
                if (!existingTenant.displayName().equals(normalizedDisplayName)) {
                    throw new IdempotencyConflictException(
                            "Idempotency key '%s' was already used with a different workspace name".formatted(normalizedKey)
                    );
                }
                TenantMembership membership = tenantMembershipRepository.findByTenantIdAndIdentityId(record.tenantId(), identityId)
                        .orElseThrow(() -> new IllegalStateException("Owner membership not found for provisioned tenant"));
                return new ProvisionedWorkspace(
                        record.tenantId(), existingTenant.displayName(), membership.id(), membership.role(), false
                );
            });
        }

        // Only a request that would create a workspace is capped: a replay above answers as before, at any count.
        if (workspaceProvisioningRepository.countByIdentityId(identityId) >= maxWorkspacesPerIdentity) {
            throw new WorkspaceQuotaExceededException();
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

        // Tenant, owner membership, and provisioning record are all RLS-protected by the new tenant's ID.
        tenantContextProvider.callWithTenantId(tenantId, () -> {
            tenantRepository.save(tenant);
            tenantMembershipRepository.save(ownerMembership);
            workspaceProvisioningRepository.save(record);
            return null;
        });
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
