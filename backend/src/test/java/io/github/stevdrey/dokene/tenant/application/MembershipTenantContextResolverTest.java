package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MembershipTenantContextResolverTest {

    private final TenantMembershipDiscovery tenantMembershipDiscovery = Mockito.mock(TenantMembershipDiscovery.class);
    private final MembershipTenantContextResolver resolver = new MembershipTenantContextResolver(tenantMembershipDiscovery);

    @Test
    void resolvesContextFromAnActiveTenantAndActiveServerSideMembership() {
        TenantId tenantId = TenantId.random();
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        TenantMembershipId membershipId = TenantMembershipId.random();
        when(tenantMembershipDiscovery.findActiveMemberships(identityId)).thenReturn(List.of(
                new TenantMembershipDiscovery.ActiveTenantMembership(
                        tenantId, "Workspace", membershipId, TenantRole.OPERATOR
                )
        ));

        TenantContext context = resolver.resolve(identityId, tenantId);

        assertThat(context.tenantId()).isEqualTo(tenantId);
        assertThat(context.identityId()).isEqualTo(identityId);
        assertThat(context.membershipId()).isEqualTo(membershipId);
        assertThat(context.role()).isEqualTo(TenantRole.OPERATOR);
    }

    @Test
    void rejectsAnUnknownOrInactiveTenantBeforeAContextExists() {
        TenantId tenantId = TenantId.random();
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        when(tenantMembershipDiscovery.findActiveMemberships(identityId)).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolve(identityId, tenantId))
                .isInstanceOf(TenantContextAuthorizationException.class);
    }

    @Test
    void rejectsMissingOrInactiveMemberships() {
        TenantId tenantId = TenantId.random();
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        when(tenantMembershipDiscovery.findActiveMemberships(identityId)).thenReturn(List.of(
                new TenantMembershipDiscovery.ActiveTenantMembership(
                        TenantId.random(), "Other workspace", TenantMembershipId.random(), TenantRole.VIEWER
                )
        ));

        assertThatThrownBy(() -> resolver.resolve(identityId, tenantId))
                .isInstanceOf(TenantContextAuthorizationException.class);
    }
}
