package io.github.stevdrey.dokene.tenant.domain;

import java.time.Instant;
import java.util.OptionalLong;

public final class Tenant {

    public static final int DISPLAY_NAME_MAX_LENGTH = 160;

    private final TenantId id;
    private final String displayName;
    private final Instant createdAt;
    private final Long revision;
    private TenantStatus status;
    private Instant updatedAt;

    private Tenant(
            TenantId id,
            String displayName,
            TenantStatus status,
            Instant createdAt,
            Instant updatedAt,
            Long revision
    ) {
        this.id = required(id, "Tenant ID is required");
        this.displayName = normalizeDisplayName(displayName);
        this.status = required(status, "Tenant status is required");
        this.createdAt = required(createdAt, "Tenant creation timestamp is required");
        this.updatedAt = required(updatedAt, "Tenant update timestamp is required");
        this.revision = revision;

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Tenant update timestamp cannot precede creation timestamp");
        }
        if (revision != null && revision < 0) {
            throw new IllegalArgumentException("Tenant revision cannot be negative");
        }
    }

    public static Tenant create(TenantId id, String displayName, Instant createdAt) {
        return new Tenant(id, displayName, TenantStatus.ACTIVE, createdAt, createdAt, null);
    }

    public static Tenant restore(
            TenantId id,
            String displayName,
            TenantStatus status,
            Instant createdAt,
            Instant updatedAt,
            long revision
    ) {
        return new Tenant(id, displayName, status, createdAt, updatedAt, revision);
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

    public OptionalLong revision() {
        return revision == null ? OptionalLong.empty() : OptionalLong.of(revision);
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

        int start = 0;
        int end = displayName.length();
        while (start < end) {
            int codePoint = displayName.codePointAt(start);
            if (!isDisplayNameWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = displayName.codePointBefore(end);
            if (!isDisplayNameWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }

        String normalized = displayName.substring(start, end);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Tenant display name cannot be blank");
        }
        if (normalized.codePointCount(0, normalized.length()) > DISPLAY_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Tenant display name cannot exceed %d characters".formatted(DISPLAY_NAME_MAX_LENGTH));
        }
        return normalized;
    }

    private static boolean isDisplayNameWhitespace(int codePoint) {
        return codePoint == 0x0085 || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static <T> T required(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
