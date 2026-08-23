package io.github.stevdrey.dokene.tenant.persistence.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataTenantMembershipRepository extends JpaRepository<TenantMembershipEntity, UUID> {

    Optional<TenantMembershipEntity> findByTenantIdAndIdentityId(UUID tenantId, UUID identityId);
}
