package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextAuthorizationException;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Converts an authenticated request and requested tenant selector into a verified tenant context.
 */
class TenantContextRequestFilter extends OncePerRequestFilter {

    static final String TENANT_ID_HEADER = "X-Tenant-Id";

    private final TenantContextProvider tenantContexts;
    private final TenantContextResolver tenantContextResolver;
    private final AuthenticatedTenantIdentityResolver identityResolver;
    private final RequestMatcher tenantScopedRequestMatcher;
    private final AuditRecorder auditRecorder;

    private static final AuditRecorder NO_OP_AUDIT = new AuditRecorder() {
        @Override
        public void authorizationDenied(TenantPermission permission, AuditDenialReason reason) {
        }

        @Override
        public void membershipRoleChanged(
                io.github.stevdrey.dokene.tenant.domain.TenantMembershipId target,
                io.github.stevdrey.dokene.tenant.domain.TenantRole previousRole,
                io.github.stevdrey.dokene.tenant.domain.TenantRole newRole
        ) {
        }

        @Override
        public void customerMutated(UUID target, AuditEventType eventType) {
        }
    };

    TenantContextRequestFilter(
            TenantContextProvider tenantContexts,
            TenantContextResolver tenantContextResolver,
            AuthenticatedTenantIdentityResolver identityResolver,
            RequestMatcher tenantScopedRequestMatcher
    ) {
        this(tenantContexts, tenantContextResolver, identityResolver, tenantScopedRequestMatcher, NO_OP_AUDIT);
    }

    TenantContextRequestFilter(
            TenantContextProvider tenantContexts,
            TenantContextResolver tenantContextResolver,
            AuthenticatedTenantIdentityResolver identityResolver,
            RequestMatcher tenantScopedRequestMatcher,
            AuditRecorder auditRecorder
    ) {
        this.tenantContexts = Objects.requireNonNull(tenantContexts, "Tenant context provider is required");
        this.tenantContextResolver = Objects.requireNonNull(tenantContextResolver, "Tenant context resolver is required");
        this.identityResolver = Objects.requireNonNull(identityResolver, "Identity resolver is required");
        this.tenantScopedRequestMatcher = Objects.requireNonNull(tenantScopedRequestMatcher, "Tenant scoped request matcher is required");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "Audit recorder is required");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isAuthenticated(authentication)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!tenantScopedRequestMatcher.matches(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        List<String> selectors = Collections.list(request.getHeaders(TENANT_ID_HEADER));
        if (selectors.isEmpty()) {
            auditRecorder.authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (selectors.size() != 1) {
            auditRecorder.authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        TenantId requestedTenantId = parseTenantId(selectors.getFirst(), response);
        if (requestedTenantId == null) {
            return;
        }

        try {
            TenantContext tenantContext = tenantContextResolver.resolve(
                    identityResolver.resolve(authentication).orElseThrow(TenantContextAuthorizationException::new),
                    requestedTenantId
            );
            try {
                tenantContexts.callWithContext(tenantContext, () -> {
                    filterChain.doFilter(request, response);
                    return null;
                });
            } catch (ServletException | IOException | RuntimeException | Error exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ServletException(exception);
            }
        } catch (TenantContextAuthorizationException exception) {
            auditRecorder.authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private TenantId parseTenantId(String selector, HttpServletResponse response) throws IOException {
        try {
            return new TenantId(UUID.fromString(selector));
        } catch (IllegalArgumentException exception) {
            auditRecorder.authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }
    }
}
