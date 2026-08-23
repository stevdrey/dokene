package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
class TenantEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "display_name", nullable = false, length = Tenant.DISPLAY_NAME_MAX_LENGTH)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TenantStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantEntity() {
    }

    private TenantEntity(UUID id, String displayName, TenantStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.displayName = displayName;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    static TenantEntity fromDomain(Tenant tenant) {
        return new TenantEntity(
                tenant.id().value(),
                tenant.displayName(),
                tenant.status(),
                tenant.createdAt(),
                tenant.updatedAt()
        );
    }

    Tenant toDomain() {
        return Tenant.restore(new TenantId(id), displayName, status, createdAt, updatedAt);
    }
}
