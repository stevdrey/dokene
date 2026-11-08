package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    public List<TenantMembership> findAllByTenantId(TenantId tenantId) {
        return repository.findByTenantIdOrderByCreatedAtAsc(tenantId.value()).stream()
                .map(TenantMembershipEntity::toDomain)
                .toList();
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

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForMembershipChange(TenantId tenantId, Collection<IdentityId> identityIds) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        List<UUID> identities = identityIds.stream().map(IdentityId::value).distinct().toList();
        repository.shareLockTenant(tenantId.value());
        if (identities.isEmpty()) {
            return;
        }
        repository.lockByTenantIdAndIdentityIds(tenantId.value(), identities);
    }
}
