package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MembershipTenantContextResolverTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-29T00:00:00Z");

    private final TenantRepository tenantRepository = Mockito.mock(TenantRepository.class);
    private final TenantMembershipRepository membershipRepository = Mockito.mock(TenantMembershipRepository.class);
    private final ScopedValueTenantContextProvider tenantContextProvider = new ScopedValueTenantContextProvider();
    private final MembershipTenantContextResolver resolver = new MembershipTenantContextResolver(
            tenantRepository, membershipRepository, tenantContextProvider
    );

    @Test
    void resolvesContextFromAnActiveTenantAndActiveServerSideMembership() {
        TenantId tenantId = TenantId.random();
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        Tenant tenant = Tenant.create(tenantId, "Workspace", CREATED_AT);
        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(), tenantId, identityId, TenantRole.OPERATOR, CREATED_AT
        );
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByTenantIdAndIdentityId(tenantId, identityId)).thenReturn(Optional.of(membership));

        TenantContext context = resolver.resolve(identityId, tenantId);

        assertThat(context.tenantId()).isEqualTo(tenantId);
        assertThat(context.identityId()).isEqualTo(identityId);
        assertThat(context.membershipId()).isEqualTo(membership.id());
        assertThat(context.role()).isEqualTo(TenantRole.OPERATOR);
    }

    @Test
    void rejectsAnUnknownOrInactiveTenantBeforeAContextExists() {
        TenantId tenantId = TenantId.random();
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(identityId, tenantId))
                .isInstanceOf(TenantContextAuthorizationException.class);
    }

    @Test
    void rejectsMissingOrInactiveMemberships() {
        TenantId tenantId = TenantId.random();
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        Tenant tenant = Tenant.create(tenantId, "Workspace", CREATED_AT);
        TenantMembership inactiveMembership = TenantMembership.invite(
                TenantMembershipId.random(), tenantId, identityId, TenantRole.VIEWER, CREATED_AT
        );
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(membershipRepository.findByTenantIdAndIdentityId(tenantId, identityId))
                .thenReturn(Optional.of(inactiveMembership));

        assertThatThrownBy(() -> resolver.resolve(identityId, tenantId))
                .isInstanceOf(TenantContextAuthorizationException.class);
    }
}
