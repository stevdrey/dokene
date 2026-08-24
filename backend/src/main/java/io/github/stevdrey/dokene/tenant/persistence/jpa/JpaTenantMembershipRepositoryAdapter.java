package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.stereotype.Repository;

@Repository
public class JpaTenantMembershipRepositoryAdapter implements TenantMembershipRepository {

    private final SpringDataTenantMembershipRepository repository;

    JpaTenantMembershipRepositoryAdapter(SpringDataTenantMembershipRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<TenantMembership> findByTenantIdAndIdentityId(TenantId tenantId, IdentityId identityId) {
        return repository.findByTenantIdAndIdentityId(tenantId.value(), identityId.value())
                .map(TenantMembershipEntity::toDomain);
    }

    @Override
    public TenantMembership save(TenantMembership membership) {
        OptionalLong previousRevision = membership.revision();
        TenantMembership persistedMembership = repository.saveAndFlush(TenantMembershipEntity.fromDomain(membership)).toDomain();
        AggregateRevisionSynchronization.synchronize(
                membership,
                previousRevision,
                membership::synchronizeRevision,
                membership::restoreRevision,
                persistedMembership.revision().orElseThrow()
        );
        return membership;
    }
}
