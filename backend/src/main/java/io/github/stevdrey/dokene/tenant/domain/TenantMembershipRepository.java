package io.github.stevdrey.dokene.tenant.domain;

import java.util.Optional;

public interface TenantMembershipRepository {

    Optional<TenantMembership> findByTenantIdAndIdentityId(TenantId tenantId, IdentityId identityId);

    TenantMembership save(TenantMembership membership);
}
