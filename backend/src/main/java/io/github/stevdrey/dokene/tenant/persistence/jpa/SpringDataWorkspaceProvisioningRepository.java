package io.github.stevdrey.dokene.tenant.persistence.jpa;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataWorkspaceProvisioningRepository extends JpaRepository<WorkspaceProvisioningEntity, UUID> {
}
