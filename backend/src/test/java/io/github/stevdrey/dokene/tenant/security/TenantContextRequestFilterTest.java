package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextAuthorizationException;
import io.github.stevdrey.dokene.tenant.application.ThreadLocalTenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TenantContextRequestFilterTest {

    private final ThreadLocalTenantContextProvider tenantContexts = new ThreadLocalTenantContextProvider();
    private final TenantId tenantId = TenantId.random();
    private final IdentityId identityId = new IdentityId(UUID.randomUUID());
    private final TenantContext context = new TenantContext(
            tenantId, identityId, TenantMembershipId.random(), TenantRole.ADMIN
    );

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsMissingTenantSelection() throws Exception {
        authenticate(identityId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter((identity, requestedTenant) -> context).doFilter(requestWithoutTenant(), response, (request, result) -> {
            throw new AssertionError("The filter chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(tenantContexts.current()).isEmpty();
    }

    @Test
    void rejectsMalformedTenantSelection() throws Exception {
        authenticate(identityId);
        MockHttpServletRequest request = requestWithoutTenant();
        request.addHeader(TenantContextRequestFilter.TENANT_ID_HEADER, "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter((identity, requestedTenant) -> context).doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("The filter chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(tenantContexts.current()).isEmpty();
    }

    @Test
    void rejectsARequestedTenantThatDoesNotMatchAuthenticatedMembership() throws Exception {
        authenticate(identityId);
        MockHttpServletRequest request = requestFor(tenantId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter((identity, requestedTenant) -> {
            throw new TenantContextAuthorizationException();
        }).doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("The filter chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(tenantContexts.current()).isEmpty();
    }

    @Test
    void makesOnlyTheVerifiedContextAvailableAndClearsItAfterTheRequest() throws Exception {
        authenticate(identityId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter((identity, requestedTenant) -> {
            assertThat(identity).isEqualTo(identityId);
            assertThat(requestedTenant).isEqualTo(tenantId);
            return context;
        }).doFilter(requestFor(tenantId), response, (request, result) -> {
            assertThat(tenantContexts.requireCurrent()).isEqualTo(context);
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(tenantContexts.current()).isEmpty();
    }

    @Test
    void clearsTheContextWhenTheRequestFails() {
        authenticate(identityId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter((identity, requestedTenant) -> context).doFilter(
                requestFor(tenantId), response, (request, result) -> {
                    assertThat(tenantContexts.requireCurrent()).isEqualTo(context);
                    throw new IllegalStateException("downstream failure");
                }
        )).isInstanceOf(IllegalStateException.class);

        assertThat(tenantContexts.current()).isEmpty();
    }

    private TenantContextRequestFilter filter(io.github.stevdrey.dokene.tenant.application.TenantContextResolver resolver) {
        AuthenticatedTenantIdentityResolver identities = authentication -> {
            if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedTenantIdentity identity) {
                return Optional.of(identity.identityId());
            }
            return Optional.empty();
        };
        return new TenantContextRequestFilter(tenantContexts, resolver, identities);
    }

    private MockHttpServletRequest requestWithoutTenant() {
        return new MockHttpServletRequest("GET", "/api/tenant-resource");
    }

    private MockHttpServletRequest requestFor(TenantId requestedTenantId) {
        MockHttpServletRequest request = requestWithoutTenant();
        request.addHeader(TenantContextRequestFilter.TENANT_ID_HEADER, requestedTenantId.value().toString());
        return request;
    }

    private void authenticate(IdentityId authenticatedIdentityId) {
        AuthenticatedTenantIdentity principal = () -> authenticatedIdentityId;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "not-used", java.util.List.of())
        );
    }
}
