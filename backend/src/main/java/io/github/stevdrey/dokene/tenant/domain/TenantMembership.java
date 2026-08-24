package io.github.stevdrey.dokene.tenant.domain;

import java.time.Instant;
import java.util.OptionalLong;

public final class TenantMembership {

    private final TenantMembershipId id;
    private final TenantId tenantId;
    private final IdentityId identityId;
    private final TenantRole role;
    private final Instant createdAt;
    private final Long revision;
    private TenantMembershipStatus status;
    private Instant updatedAt;

    private TenantMembership(
            TenantMembershipId id,
            TenantId tenantId,
            IdentityId identityId,
            TenantRole role,
            TenantMembershipStatus status,
            Instant createdAt,
            Instant updatedAt,
            Long revision
    ) {
        this.id = required(id, "Tenant membership ID is required");
        this.tenantId = required(tenantId, "Tenant membership tenant ID is required");
        this.identityId = required(identityId, "Tenant membership identity ID is required");
        this.role = required(role, "Tenant membership role is required");
        this.status = required(status, "Tenant membership status is required");
        this.createdAt = required(createdAt, "Tenant membership creation timestamp is required");
        this.updatedAt = required(updatedAt, "Tenant membership update timestamp is required");
        this.revision = revision;

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Tenant membership update timestamp cannot precede creation timestamp");
        }
        if (revision != null && revision < 0) {
            throw new IllegalArgumentException("Tenant membership revision cannot be negative");
        }
    }

    public static TenantMembership invite(
            TenantMembershipId id,
            TenantId tenantId,
            IdentityId identityId,
            TenantRole role,
            Instant createdAt
    ) {
        return new TenantMembership(id, tenantId, identityId, role, TenantMembershipStatus.INVITED, createdAt, createdAt, null);
    }

    public static TenantMembership createActive(
            TenantMembershipId id,
            TenantId tenantId,
            IdentityId identityId,
            TenantRole role,
            Instant createdAt
    ) {
        return new TenantMembership(id, tenantId, identityId, role, TenantMembershipStatus.ACTIVE, createdAt, createdAt, null);
    }

    public static TenantMembership restore(
            TenantMembershipId id,
            TenantId tenantId,
            IdentityId identityId,
            TenantRole role,
            TenantMembershipStatus status,
            Instant createdAt,
            Instant updatedAt,
            long revision
    ) {
        return new TenantMembership(id, tenantId, identityId, role, status, createdAt, updatedAt, revision);
    }

    public TenantMembershipId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public IdentityId identityId() {
        return identityId;
    }

    public TenantRole role() {
        return role;
    }

    public TenantMembershipStatus status() {
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

    public void activate(Instant occurredAt) {
        transitionTo(TenantMembershipStatus.ACTIVE, occurredAt);
    }

    public void suspend(Instant occurredAt) {
        transitionTo(TenantMembershipStatus.SUSPENDED, occurredAt);
    }

    public void revoke(Instant occurredAt) {
        if (status != TenantMembershipStatus.INVITED && status != TenantMembershipStatus.ACTIVE
                && status != TenantMembershipStatus.SUSPENDED) {
            throw new IllegalStateException("Only invited, active, or suspended memberships can be revoked");
        }
        updateStatus(TenantMembershipStatus.REVOKED, occurredAt);
    }

    private void transitionTo(TenantMembershipStatus targetStatus, Instant occurredAt) {
        boolean allowed = (status == TenantMembershipStatus.INVITED && targetStatus == TenantMembershipStatus.ACTIVE)
                || (status == TenantMembershipStatus.ACTIVE && targetStatus == TenantMembershipStatus.SUSPENDED)
                || (status == TenantMembershipStatus.SUSPENDED && targetStatus == TenantMembershipStatus.ACTIVE);

        if (!allowed) {
            throw new IllegalStateException(
                    "Tenant membership transition from %s to %s is not allowed".formatted(status, targetStatus)
            );
        }
        updateStatus(targetStatus, occurredAt);
    }

    private void updateStatus(TenantMembershipStatus targetStatus, Instant occurredAt) {
        Instant transitionTime = required(occurredAt, "Tenant membership transition timestamp is required");
        if (transitionTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "Tenant membership transition timestamp cannot precede the current update timestamp"
            );
        }

        status = targetStatus;
        updatedAt = transitionTime;
    }

    private static <T> T required(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
