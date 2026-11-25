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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Currency;
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

    /** ISO 4217 code, or null when the tenant has none set (V44, issue 452). */
    @Column(name = "operating_currency", length = 3)
    private String operatingCurrency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long revision;

    protected TenantEntity() {
    }

    private TenantEntity(UUID id, String displayName, TenantStatus status, String operatingCurrency,
            Instant createdAt, Instant updatedAt, Long revision) {
        this.id = id;
        this.displayName = displayName;
        this.status = status;
        this.operatingCurrency = operatingCurrency;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.revision = revision;
    }

    static TenantEntity fromDomain(Tenant tenant) {
        Long revision = tenant.revision().isPresent() ? tenant.revision().getAsLong() : null;
        return new TenantEntity(
                tenant.id().value(),
                tenant.displayName(),
                tenant.status(),
                tenant.operatingCurrency().map(Currency::getCurrencyCode).orElse(null),
                tenant.createdAt(),
                tenant.updatedAt(),
                revision
        );
    }

    Tenant toDomain() {
        return Tenant.restore(new TenantId(id), displayName, status,
                operatingCurrency == null ? null : Tenant.operatingCurrencyOf(operatingCurrency),
                createdAt, updatedAt, revision);
    }
}
