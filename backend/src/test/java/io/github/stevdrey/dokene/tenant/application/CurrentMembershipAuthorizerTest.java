// ABOUTME: Unit tests for CurrentMembershipAuthorizer: a command is authorized against the membership as it is now,
// ABOUTME: and every refusal (gone, replaced, demoted, no context) is audited through the authorization listener.
package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrentMembershipAuthorizerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final TenantContextResolver resolver = mock(TenantContextResolver.class);
    private final List<AuthorizationDeniedEvent> denials = new ArrayList<>();
    private final AuthorizationAuditListener audits = denials::add;
    private final TenantAuthorizationService authorization = new DefaultTenantAuthorizationService(contexts,
            AuthorizationAuditListener.noop(), clock);
    private final CurrentMembershipAuthorizer authorizer = new CurrentMembershipAuthorizer(contexts, resolver,
            authorization, audits, clock);

    private final TenantId tenantId = TenantId.random();
    private final TenantContext request = new TenantContext(tenantId, new IdentityId(UUID.randomUUID()),
            TenantMembershipId.random(), TenantRole.OPERATOR);
    private final TenantScopedResource resource = () -> tenantId;

    @Test
    void returnsTheFreshContextWhenTheMembershipStillAllowsIt() {
        when(contexts.current()).thenReturn(Optional.of(request));
        TenantContext fresh = new TenantContext(tenantId, request.identityId(), request.membershipId(),
                TenantRole.ADMIN);
        when(resolver.resolve(request.identityId(), tenantId)).thenReturn(fresh);

        assertThat(authorizer.requireResourceAccess(TenantPermission.MESSAGE_SEND, resource)).isEqualTo(fresh);
        assertThat(denials).isEmpty();
    }

    @Test
    void aMembershipThatIsNoLongerActiveIsRefusedAndAudited() {
        when(contexts.current()).thenReturn(Optional.of(request));
        when(resolver.resolve(request.identityId(), tenantId)).thenThrow(new TenantContextAuthorizationException());

        assertDenied();
        assertThat(denials).singleElement().satisfies(event -> {
            assertThat(event.reason()).isEqualTo(CurrentMembershipAuthorizer.MEMBERSHIP_GONE);
            assertThat(event.membershipId()).isEqualTo(request.membershipId());
            assertThat(event.requiredPermission()).isEqualTo(TenantPermission.MESSAGE_SEND);
        });
    }

    @Test
    void aReinvitedMemberDoesNotCountAsTheOneWhoMadeTheRequest() {
        when(contexts.current()).thenReturn(Optional.of(request));
        when(resolver.resolve(request.identityId(), tenantId)).thenReturn(new TenantContext(tenantId,
                request.identityId(), TenantMembershipId.random(), TenantRole.OWNER));

        assertDenied();
        assertThat(denials).singleElement()
                .satisfies(event -> assertThat(event.reason()).isEqualTo(CurrentMembershipAuthorizer.MEMBERSHIP_GONE));
    }

    @Test
    void aDemotedMemberIsRefusedWithTheRoleReason() {
        when(contexts.current()).thenReturn(Optional.of(request));
        when(resolver.resolve(request.identityId(), tenantId)).thenReturn(new TenantContext(tenantId,
                request.identityId(), request.membershipId(), TenantRole.VIEWER));

        assertDenied();
        assertThat(denials).singleElement().satisfies(event -> assertThat(event.reason())
                .isEqualTo("Role VIEWER lacks permission MESSAGE_SEND"));
    }

    @Test
    void noContextIsRefusedWithoutResolvingAnything() {
        when(contexts.current()).thenReturn(Optional.empty());

        assertDenied();
        verifyNoInteractions(resolver);
        assertThat(denials).singleElement()
                .satisfies(event -> assertThat(event.reason()).isEqualTo("No active tenant context"));
    }

    private void assertDenied() {
        assertThatThrownBy(() -> authorizer.requireResourceAccess(TenantPermission.MESSAGE_SEND, resource))
                .isInstanceOf(TenantAccessDeniedException.class);
    }
}
