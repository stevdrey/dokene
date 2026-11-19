package io.github.stevdrey.dokene.tenant.domain;

import java.util.Optional;

/**
 * Repository contract for workspace provisioning records.
 */
public interface WorkspaceProvisioningRepository {

    /**
     * Serializes every provisioning request of one identity until the current transaction completes. One lock per
     * identity covers both the idempotency lookup and the per-identity cap: two requests with different keys cannot
     * both read a count below the cap and both create a workspace (ADR 0033 §6). The idempotency key is locked too,
     * after the identity, so a backend of the previous release, which locks only identity and key, still excludes the
     * same request during a rolling deploy.
     */
    void acquireProvisioningLock(IdentityId identityId, String idempotencyKey);

    Optional<WorkspaceProvisioningRecord> findByIdentityIdAndIdempotencyKey(IdentityId identityId, String idempotencyKey);

    /** The number of workspaces this identity has provisioned so far; records are never deleted. */
    long countByIdentityId(IdentityId identityId);

    WorkspaceProvisioningRecord save(WorkspaceProvisioningRecord record);
}
