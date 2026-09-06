package io.github.stevdrey.dokene.tenant.domain;

import java.util.Optional;

/**
 * Repository contract for workspace provisioning records.
 */
public interface WorkspaceProvisioningRepository {

    Optional<WorkspaceProvisioningRecord> findByIdentityIdAndIdempotencyKey(IdentityId identityId, String idempotencyKey);

    WorkspaceProvisioningRecord save(WorkspaceProvisioningRecord record);
}
