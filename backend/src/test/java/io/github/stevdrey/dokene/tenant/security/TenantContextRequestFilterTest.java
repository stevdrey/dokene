package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.tenant.application.ScopedValueTenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextAuthorizationException;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

class TenantContextRequestFilterTest {

    private final ScopedValueTenantContextProvider tenantContexts = new ScopedValueTenantContextProvider();
    private final TenantScopedRequestMatcher requestMatcher = new TenantScopedRequestMatcher();
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
    void allowsAuthenticatedGlobalRequestWithoutTenantHeader() throws Exception {
        authenticate(identityId);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean filterChainExecuted = new AtomicBoolean(false);

        filter((identity, requestedTenant) -> {
            throw new AssertionError("Resolver must not be called for global endpoints");
        }).doFilter(request, response, (req, res) -> {
            filterChainExecuted.set(true);
            assertThat(tenantContexts.current()).isEmpty();
        });

        assertThat(filterChainExecuted).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(tenantContexts.current()).isEmpty();
    }

    @Test
    void allowsUnauthenticatedRequestWithoutTenantCheck() throws Exception {
        MockHttpServletRequest request = requestWithoutTenant();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean filterChainExecuted = new AtomicBoolean(false);

        filter((identity, requestedTenant) -> context).doFilter(request, response, (req, res) -> {
            filterChainExecuted.set(true);
            assertThat(tenantContexts.current()).isEmpty();
        });

        assertThat(filterChainExecuted).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(tenantContexts.current()).isEmpty();
    }

    @Test
    void rejectsMissingTenantSelectionOnTenantScopedEndpoint() throws Exception {
        authenticate(identityId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuditRecorder audit = mock();

        filter((identity, requestedTenant) -> context, audit).doFilter(requestWithoutTenant(), response, (request, result) -> {
            throw new AssertionError("The filter chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(tenantContexts.current()).isEmpty();
        verify(audit).authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
    }

    @Test
    void rejectsMalformedTenantSelection() throws Exception {
        authenticate(identityId);
        MockHttpServletRequest request = requestWithoutTenant();
        request.addHeader(TenantContextRequestFilter.TENANT_ID_HEADER, "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuditRecorder audit = mock();

        filter((identity, requestedTenant) -> context, audit).doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("The filter chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(tenantContexts.current()).isEmpty();
        verify(audit).authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
    }

    @Test
    void rejectsMultipleTenantHeaders() throws Exception {
        authenticate(identityId);
        MockHttpServletRequest request = requestWithoutTenant();
        request.addHeader(TenantContextRequestFilter.TENANT_ID_HEADER, tenantId.value().toString());
        request.addHeader(TenantContextRequestFilter.TENANT_ID_HEADER, TenantId.random().value().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuditRecorder audit = mock();

        filter((identity, requestedTenant) -> context, audit).doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("The filter chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(tenantContexts.current()).isEmpty();
        verify(audit).authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
    }

    @Test
    void rejectsARequestedTenantThatDoesNotMatchAuthenticatedMembership() throws Exception {
        authenticate(identityId);
        MockHttpServletRequest request = requestFor(tenantId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuditRecorder audit = mock();

        filter((identity, requestedTenant) -> {
            throw new TenantContextAuthorizationException();
        }, audit).doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("The filter chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(tenantContexts.current()).isEmpty();
        verify(audit).authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
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

    @Test
    void letsTheOwningResponderWriteEachRejectionAfterAuditing() throws Exception {
        authenticate(identityId);
        MockHttpServletRequest duplicated = requestFor(tenantId);
        duplicated.addHeader(TenantContextRequestFilter.TENANT_ID_HEADER, TenantId.random().value().toString());
        MockHttpServletRequest malformed = requestWithoutTenant();
        malformed.addHeader(TenantContextRequestFilter.TENANT_ID_HEADER, "not-a-uuid");

        assertRejectedBy(requestWithoutTenant(), (identity, requestedTenant) -> context, 403,
                "{\"rejection\":\"ACCESS_DENIED\"}");
        assertRejectedBy(duplicated, (identity, requestedTenant) -> context, 400,
                "{\"rejection\":\"INVALID_SELECTOR\"}");
        assertRejectedBy(malformed, (identity, requestedTenant) -> context, 400,
                "{\"rejection\":\"INVALID_SELECTOR\"}");
        assertRejectedBy(requestFor(tenantId), (identity, requestedTenant) -> {
            throw new TenantContextAuthorizationException();
        }, 403, "{\"rejection\":\"ACCESS_DENIED\"}");
    }

    @Test
    void keepsTheEmptyBodyErrorWhenNoResponderOwnsTheRoute() throws Exception {
        authenticate(identityId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuditRecorder audit = mock();

        filter((identity, requestedTenant) -> context, audit, List.of(new RecordingResponder("/api/elsewhere/**")))
                .doFilter(requestWithoutTenant(), response, (request, result) -> {
                    throw new AssertionError("The filter chain must not run");
                });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEmpty();
        verify(audit).authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
    }

    @Test
    void keepsTheEmptyBodyErrorWhenSeveralRespondersClaimTheRoute() throws Exception {
        authenticate(identityId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuditRecorder audit = mock();

        filter((identity, requestedTenant) -> context, audit, List.of(new RecordingResponder("/api/tenant-resource"),
                new RecordingResponder("/api/**")))
                .doFilter(requestWithoutTenant(), response, (request, result) -> {
                    throw new AssertionError("The filter chain must not run");
                });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEmpty();
        verify(audit).authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
    }

    private void assertRejectedBy(
            MockHttpServletRequest request,
            TenantContextResolver resolver,
            int expectedStatus,
            String expectedBody
    ) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuditRecorder audit = mock();

        filter(resolver, audit, List.of(new RecordingResponder("/api/elsewhere/**"),
                new RecordingResponder("/api/tenant-resource")))
                .doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
                    throw new AssertionError("The filter chain must not run");
                });

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).isEqualTo(expectedBody);
        assertThat(response.isCommitted()).isTrue();
        assertThat(tenantContexts.current()).isEmpty();
        verify(audit).authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
    }

    @Test
    void refusesAResponderWhoseBodyStatusContradictsTheRejection() {
        assertThatThrownBy(() -> new RecordingResponder("/api/tenant-resource", rejection -> 404))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Echoes the rejection so a test can tell which responder answered and with which rejection. */
    private static final class RecordingResponder extends ProblemJsonTenantSelectionResponder {
        private record Echo(TenantSelectionRejection rejection) {
        }

        RecordingResponder(String pattern) {
            this(pattern, TenantSelectionRejection::status);
        }

        RecordingResponder(String pattern, ToIntFunction<TenantSelectionRejection> bodyStatus) {
            super(PathPatternRequestMatcher.pathPattern(pattern), Echo::new,
                    echo -> bodyStatus.applyAsInt(echo.rejection()));
        }
    }

    private TenantContextRequestFilter filter(TenantContextResolver resolver) {
        return filter(resolver, mock());
    }

    private TenantContextRequestFilter filter(TenantContextResolver resolver, AuditRecorder auditRecorder) {
        return filter(resolver, auditRecorder, List.of());
    }

    private TenantContextRequestFilter filter(
            TenantContextResolver resolver,
            AuditRecorder auditRecorder,
            List<TenantSelectionRejectionResponder> responders
    ) {
        AuthenticatedTenantIdentityResolver identities = authentication -> {
            if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedTenantIdentity identity) {
                return Optional.of(identity.identityId());
            }
            return Optional.empty();
        };
        return new TenantContextRequestFilter(
                tenantContexts, resolver, identities, requestMatcher, auditRecorder, responders);
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
