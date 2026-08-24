package io.github.stevdrey.dokene.tenant.domain;

import java.time.Instant;

public final class Tenant {

    public static final int DISPLAY_NAME_MAX_LENGTH = 160;

    private final TenantId id;
    private final String displayName;
    private final Instant createdAt;
    private TenantStatus status;
    private Instant updatedAt;

    private Tenant(
            TenantId id,
            String displayName,
            TenantStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = required(id, "Tenant ID is required");
        this.displayName = normalizeDisplayName(displayName);
        this.status = required(status, "Tenant status is required");
        this.createdAt = required(createdAt, "Tenant creation timestamp is required");
        this.updatedAt = required(updatedAt, "Tenant update timestamp is required");

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Tenant update timestamp cannot precede creation timestamp");
        }
    }

    public static Tenant create(TenantId id, String displayName, Instant createdAt) {
        return new Tenant(id, displayName, TenantStatus.ACTIVE, createdAt, createdAt);
    }

    public static Tenant restore(
            TenantId id,
            String displayName,
            TenantStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new Tenant(id, displayName, status, createdAt, updatedAt);
    }

    public TenantId id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public TenantStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void suspend(Instant occurredAt) {
        transitionTo(TenantStatus.SUSPENDED, occurredAt);
    }

    public void activate(Instant occurredAt) {
        transitionTo(TenantStatus.ACTIVE, occurredAt);
    }

    public void archive(Instant occurredAt) {
        if (status != TenantStatus.ACTIVE && status != TenantStatus.SUSPENDED) {
            throw new IllegalStateException("Only active or suspended tenants can be archived");
        }
        updateStatus(TenantStatus.ARCHIVED, occurredAt);
    }

    private void transitionTo(TenantStatus targetStatus, Instant occurredAt) {
        boolean allowed = (status == TenantStatus.ACTIVE && targetStatus == TenantStatus.SUSPENDED)
                || (status == TenantStatus.SUSPENDED && targetStatus == TenantStatus.ACTIVE);

        if (!allowed) {
            throw new IllegalStateException("Tenant transition from %s to %s is not allowed".formatted(status, targetStatus));
        }
        updateStatus(targetStatus, occurredAt);
    }

    private void updateStatus(TenantStatus targetStatus, Instant occurredAt) {
        Instant transitionTime = required(occurredAt, "Tenant transition timestamp is required");
        if (transitionTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Tenant transition timestamp cannot precede the current update timestamp");
        }

        status = targetStatus;
        updatedAt = transitionTime;
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            throw new IllegalArgumentException("Tenant display name is required");
        }

        String normalized = displayName.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Tenant display name cannot be blank");
        }
        if (normalized.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Tenant display name cannot exceed %d characters".formatted(DISPLAY_NAME_MAX_LENGTH));
        }
        return normalized;
    }

    private static <T> T required(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
