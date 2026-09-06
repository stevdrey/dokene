package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspace_provisioning_records")
class WorkspaceProvisioningEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "identity_id", nullable = false, updatable = false)
    private UUID identityId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "display_name", nullable = false, updatable = false, length = Tenant.DISPLAY_NAME_MAX_LENGTH)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkspaceProvisioningEntity() {
    }

    private WorkspaceProvisioningEntity(
            UUID id,
            String idempotencyKey,
            UUID identityId,
            UUID tenantId,
            String displayName,
            Instant createdAt
    ) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.identityId = identityId;
        this.tenantId = tenantId;
        this.displayName = displayName;
        this.createdAt = createdAt;
    }

    static WorkspaceProvisioningEntity fromDomain(WorkspaceProvisioningRecord record) {
        return new WorkspaceProvisioningEntity(
                record.id(),
                record.idempotencyKey(),
                record.identityId().value(),
                record.tenantId().value(),
                record.displayName(),
                record.createdAt()
        );
    }

    WorkspaceProvisioningRecord toDomain() {
        return new WorkspaceProvisioningRecord(
                id,
                idempotencyKey,
                new IdentityId(identityId),
                new TenantId(tenantId),
                displayName,
                createdAt
        );
    }
}
