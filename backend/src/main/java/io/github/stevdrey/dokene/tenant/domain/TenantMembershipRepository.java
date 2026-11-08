package io.github.stevdrey.dokene.tenant.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TenantMembershipRepository {

    Optional<TenantMembership> findByTenantIdAndIdentityId(TenantId tenantId, IdentityId identityId);

    List<TenantMembership> findAllByTenantId(TenantId tenantId);

    TenantMembership save(TenantMembership membership);

    /**
     * Until the current transaction ends, share-locks the tenant (a concurrent suspension waits, other membership
     * changes do not) and row-locks its memberships of {@code identityIds}, always tenant first and memberships in a
     * fixed order so membership changes never deadlock. A membership change takes this lock on its actor and its
     * target before it re-reads the actor's authority, so a concurrent role change or revocation of either one is
     * serialized with it. Identities without a membership in the tenant are ignored. Requires an active transaction.
     */
    void lockForMembershipChange(TenantId tenantId, Collection<IdentityId> identityIds);
}
