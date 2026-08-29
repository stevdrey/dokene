package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_memberships")
class TenantMembershipEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "identity_id", nullable = false, updatable = false)
    private UUID identityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private TenantRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TenantMembershipStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long revision;

    protected TenantMembershipEntity() {
    }

    private TenantMembershipEntity(
            UUID id,
            UUID tenantId,
            UUID identityId,
            TenantRole role,
            TenantMembershipStatus status,
            Instant createdAt,
            Instant updatedAt,
            Long revision
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.identityId = identityId;
        this.role = role;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.revision = revision;
    }

    static TenantMembershipEntity fromDomain(TenantMembership membership) {
        Long revision = membership.revision().isPresent() ? membership.revision().getAsLong() : null;
        return new TenantMembershipEntity(
                membership.id().value(),
                membership.tenantId().value(),
                membership.identityId().value(),
                membership.role(),
                membership.status(),
                membership.createdAt(),
                membership.updatedAt(),
                revision
        );
    }

    TenantMembership toDomain() {
        return TenantMembership.restore(
                new TenantMembershipId(id),
                new TenantId(tenantId),
                new IdentityId(identityId),
                role,
                status,
                createdAt,
                updatedAt,
                revision
        );
    }
}
