package io.github.stevdrey.dokene.tenant.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of a completed workspace provisioning request for idempotency tracking.
 */
public record WorkspaceProvisioningRecord(
        UUID id,
        String idempotencyKey,
        IdentityId identityId,
        TenantId tenantId,
        String displayName,
        Instant createdAt
) {

    public WorkspaceProvisioningRecord {
        Objects.requireNonNull(id, "Record ID is required");
        Objects.requireNonNull(idempotencyKey, "Idempotency key is required");
        Objects.requireNonNull(identityId, "Identity ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(displayName, "Display name is required");
        Objects.requireNonNull(createdAt, "Creation timestamp is required");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("Display name cannot be blank");
        }
    }
}
