package io.github.stevdrey.dokene.tenant.domain;

import java.util.Optional;

public interface TenantRepository {

    Optional<Tenant> findById(TenantId id);

    Tenant save(Tenant tenant);
}
