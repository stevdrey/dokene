package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRecord;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaWorkspaceProvisioningRepositoryAdapter implements WorkspaceProvisioningRepository {

    private final SpringDataWorkspaceProvisioningRepository repository;

    JpaWorkspaceProvisioningRepositoryAdapter(SpringDataWorkspaceProvisioningRepository repository) {
        this.repository = repository;
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
