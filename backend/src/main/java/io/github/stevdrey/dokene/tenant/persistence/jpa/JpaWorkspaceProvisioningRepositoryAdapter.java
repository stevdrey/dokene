package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRecord;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRepository;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
public class JpaWorkspaceProvisioningRepositoryAdapter implements WorkspaceProvisioningRepository {

    private final SpringDataWorkspaceProvisioningRepository repository;
    private final EntityManager entityManager;

    JpaWorkspaceProvisioningRepositoryAdapter(
            SpringDataWorkspaceProvisioningRepository repository,
            EntityManager entityManager
    ) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public void acquireIdempotencyLock(IdentityId identityId, String idempotencyKey) {
        entityManager.createNativeQuery("""
                        SELECT pg_advisory_xact_lock(
                            hashtext(CAST(:identityId AS text)),
                            hashtext(CAST(:idempotencyKey AS text))
                        )
                        """)
                .setParameter("identityId", identityId.value().toString())
                .setParameter("idempotencyKey", idempotencyKey)
                .getSingleResult();
    }

    @Override
    public Optional<WorkspaceProvisioningRecord> findByIdentityIdAndIdempotencyKey(IdentityId identityId, String idempotencyKey) {
        return repository.findByIdentityIdAndIdempotencyKey(identityId.value(), idempotencyKey)
                .map(WorkspaceProvisioningEntity::toDomain);
    }

    @Override
    public WorkspaceProvisioningRecord save(WorkspaceProvisioningRecord record) {
        return repository.saveAndFlush(WorkspaceProvisioningEntity.fromDomain(record)).toDomain();
    }
}
