package io.github.stevdrey.dokene.tenant.domain;

import java.util.Optional;

/**
 * Repository contract for workspace provisioning records.
 */
public interface WorkspaceProvisioningRepository {

    /**
     * Serializes provisioning requests for one identity and idempotency key until the current transaction completes.
     */
    void acquireIdempotencyLock(IdentityId identityId, String idempotencyKey);

    Optional<WorkspaceProvisioningRecord> findByIdentityIdAndIdempotencyKey(IdentityId identityId, String idempotencyKey);

    WorkspaceProvisioningRecord save(WorkspaceProvisioningRecord record);
}
