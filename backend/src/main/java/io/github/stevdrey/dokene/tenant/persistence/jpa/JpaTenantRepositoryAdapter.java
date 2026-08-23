package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaTenantRepositoryAdapter implements TenantRepository {

    private final SpringDataTenantRepository repository;

    JpaTenantRepositoryAdapter(SpringDataTenantRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Tenant> findById(TenantId id) {
        return repository.findById(id.value()).map(TenantEntity::toDomain);
    }

    @Override
    public Tenant save(Tenant tenant) {
        return repository.saveAndFlush(TenantEntity.fromDomain(tenant)).toDomain();
    }
}
