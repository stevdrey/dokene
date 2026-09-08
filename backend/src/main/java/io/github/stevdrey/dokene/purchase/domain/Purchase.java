package io.github.stevdrey.dokene.purchase.domain;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class Purchase implements TenantScopedResource {
    public static final int DESCRIPTION_MAX_LENGTH = 500;

    private final PurchaseId id;
    private final TenantId tenantId;
    private final CustomerId customerId;
    private Instant purchasedAt;
    private String description;
    private PurchaseStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant voidedAt;
    private long version;

    private Purchase(PurchaseId id, TenantId tenantId, CustomerId customerId, Instant purchasedAt,
            String description, PurchaseStatus status, Instant createdAt, Instant updatedAt,
            Instant voidedAt, long version) {
        this.id = Objects.requireNonNull(id, "Purchase ID is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID is required");
        this.customerId = Objects.requireNonNull(customerId, "Customer ID is required");
        this.purchasedAt = micros(purchasedAt);
        this.description = validateDescription(description);
        this.status = Objects.requireNonNull(status, "Purchase status is required");
        this.createdAt = micros(createdAt);
        this.updatedAt = micros(updatedAt);
        this.voidedAt = voidedAt == null ? null : micros(voidedAt);
        if (version < 0 || this.updatedAt.isBefore(this.createdAt)
                || ((status == PurchaseStatus.VOID) != (voidedAt != null))) {
            throw new IllegalArgumentException("Purchase state is inconsistent");
        }
        this.version = version;
    }

    public static Purchase create(PurchaseId id, TenantId tenantId, CustomerId customerId,
            Instant purchasedAt, String description, Instant now) {
        Instant timestamp = micros(now);
        return new Purchase(id, tenantId, customerId, purchasedAt, description,
                PurchaseStatus.VALID, timestamp, timestamp, null, 0);
    }

    public static Purchase restore(PurchaseId id, TenantId tenantId, CustomerId customerId,
            Instant purchasedAt, String description, PurchaseStatus status, Instant createdAt,
            Instant updatedAt, Instant voidedAt, long version) {
        return new Purchase(id, tenantId, customerId, purchasedAt, description, status,
                createdAt, updatedAt, voidedAt, version);
    }

    public void correct(Instant newPurchasedAt, String newDescription, Instant now) {
        if (status == PurchaseStatus.VOID) throw new IllegalStateException("Voided purchases cannot be corrected");
        Instant timestamp = micros(now);
        if (timestamp.isBefore(updatedAt)) throw new IllegalArgumentException("Update timestamp cannot move backwards");
        purchasedAt = micros(newPurchasedAt);
        description = validateDescription(newDescription);
        updatedAt = timestamp;
    }

    public boolean voidPurchase(Instant now) {
        if (status == PurchaseStatus.VOID) return false;
        Instant timestamp = micros(now);
        if (timestamp.isBefore(updatedAt)) throw new IllegalArgumentException("Void timestamp cannot move backwards");
        status = PurchaseStatus.VOID;
        updatedAt = timestamp;
        voidedAt = timestamp;
        return true;
    }

    public void synchronizeVersion(long value) { version = value; }

    private static String validateDescription(String value) {
        Objects.requireNonNull(value, "Purchase description is required");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > DESCRIPTION_MAX_LENGTH || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid purchase description");
        }
        return normalized;
    }

    private static Instant micros(Instant value) {
        return Objects.requireNonNull(value, "Timestamp is required").truncatedTo(ChronoUnit.MICROS);
    }

    public PurchaseId id() { return id; }
    @Override public TenantId tenantId() { return tenantId; }
    public CustomerId customerId() { return customerId; }
    public Instant purchasedAt() { return purchasedAt; }
    public String description() { return description; }
    public PurchaseStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant voidedAt() { return voidedAt; }
    public long version() { return version; }
}
