package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantStatus;
import org.springframework.stereotype.Service;

@Service
public class MembershipTenantContextResolver implements TenantContextResolver {

    private final TenantRepository tenantRepository;
    private final TenantMembershipRepository membershipRepository;
    private final TenantContextProvider tenantContextProvider;

    public MembershipTenantContextResolver(
            TenantRepository tenantRepository,
            TenantMembershipRepository membershipRepository,
            TenantContextProvider tenantContextProvider
    ) {
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.tenantContextProvider = tenantContextProvider;
    }

    @Override
    public TenantContext resolve(IdentityId identityId, TenantId requestedTenantId) {
        Tenant tenant = tenantRepository.findById(requestedTenantId)
                .filter(candidate -> candidate.status() == TenantStatus.ACTIVE)
                .orElseThrow(TenantContextAuthorizationException::new);
        TenantMembership membership = tenantContextProvider.callWithTenantId(
                tenant.id(),
                () -> membershipRepository.findByTenantIdAndIdentityId(tenant.id(), identityId)
                        .filter(candidate -> candidate.status() == TenantMembershipStatus.ACTIVE)
                        .orElseThrow(TenantContextAuthorizationException::new)
        );

        return new TenantContext(tenant.id(), identityId, membership.id(), membership.role());
    }
}
