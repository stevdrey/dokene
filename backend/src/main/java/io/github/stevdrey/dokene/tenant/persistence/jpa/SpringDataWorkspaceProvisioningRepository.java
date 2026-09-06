package io.github.stevdrey.dokene.tenant.persistence.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataWorkspaceProvisioningRepository extends JpaRepository<WorkspaceProvisioningEntity, UUID> {

    Optional<WorkspaceProvisioningEntity> findByIdentityIdAndIdempotencyKey(UUID identityId, String idempotencyKey);
}
