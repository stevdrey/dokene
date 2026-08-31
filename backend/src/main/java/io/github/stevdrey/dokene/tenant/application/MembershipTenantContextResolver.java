package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import org.springframework.stereotype.Service;

@Service
public class MembershipTenantContextResolver implements TenantContextResolver {

    private final TenantMembershipDiscovery tenantMembershipDiscovery;

    public MembershipTenantContextResolver(TenantMembershipDiscovery tenantMembershipDiscovery) {
        this.tenantMembershipDiscovery = tenantMembershipDiscovery;
    }

    @Override
    public TenantContext resolve(IdentityId identityId, TenantId requestedTenantId) {
        TenantMembershipDiscovery.ActiveTenantMembership membership = tenantMembershipDiscovery
                .findActiveMemberships(identityId)
                .stream()
                .filter(candidate -> candidate.tenantId().equals(requestedTenantId))
                .findFirst()
                .orElseThrow(TenantContextAuthorizationException::new);
        return new TenantContext(membership.tenantId(), identityId, membership.membershipId(), membership.role());
    }
}
