package io.github.stevdrey.dokene.tenant.persistence.jpa;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataTenantMembershipRepository extends JpaRepository<TenantMembershipEntity, UUID> {

    Optional<TenantMembershipEntity> findByTenantIdAndIdentityId(UUID tenantId, UUID identityId);

    List<TenantMembershipEntity> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);

    /**
     * PostgreSQL applies {@code ORDER BY} before the locking clause, so rows are locked in id order. {@code NO KEY}
     * conflicts with every membership update but leaves foreign-key checks from other tables unblocked.
     */
    @Query(value = """
            SELECT id FROM dokene.tenant_memberships
            WHERE tenant_id = :tenantId AND identity_id IN (:identityIds)
            ORDER BY id
            FOR NO KEY UPDATE
            """, nativeQuery = true)
    List<UUID> lockByTenantIdAndIdentityIds(@Param("tenantId") UUID tenantId,
            @Param("identityIds") Collection<UUID> identityIds);
}
