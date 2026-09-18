package io.github.stevdrey.dokene.tenant.domain;

import java.util.List;
import java.util.Optional;

public interface TenantMembershipRepository {

    Optional<TenantMembership> findByTenantIdAndIdentityId(TenantId tenantId, IdentityId identityId);

    List<TenantMembership> findAllByTenantId(TenantId tenantId);

    TenantMembership save(TenantMembership membership);
}
