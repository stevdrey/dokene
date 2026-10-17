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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(TenantContextRequestFilter.class);

    private final TenantContextProvider tenantContexts;
    private final TenantContextResolver tenantContextResolver;
    private final AuthenticatedTenantIdentityResolver identityResolver;
    private final RequestMatcher tenantScopedRequestMatcher;
    private final AuditRecorder auditRecorder;
    private final List<TenantSelectionRejectionResponder> rejectionResponders;

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

        @Override
        public void purchaseMutated(UUID target, AuditEventType eventType) {
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
        this(tenantContexts, tenantContextResolver, identityResolver, tenantScopedRequestMatcher, auditRecorder, List.of());
    }

    TenantContextRequestFilter(
            TenantContextProvider tenantContexts,
            TenantContextResolver tenantContextResolver,
            AuthenticatedTenantIdentityResolver identityResolver,
            RequestMatcher tenantScopedRequestMatcher,
            AuditRecorder auditRecorder,
            List<TenantSelectionRejectionResponder> rejectionResponders
    ) {
        this.tenantContexts = Objects.requireNonNull(tenantContexts, "Tenant context provider is required");
        this.tenantContextResolver = Objects.requireNonNull(tenantContextResolver, "Tenant context resolver is required");
        this.identityResolver = Objects.requireNonNull(identityResolver, "Identity resolver is required");
        this.tenantScopedRequestMatcher = Objects.requireNonNull(tenantScopedRequestMatcher, "Tenant scoped request matcher is required");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "Audit recorder is required");
        this.rejectionResponders = List.copyOf(
                Objects.requireNonNull(rejectionResponders, "Rejection responders are required"));
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
            reject(request, response, TenantSelectionRejection.ACCESS_DENIED);
            return;
        }
        if (selectors.size() != 1) {
            reject(request, response, TenantSelectionRejection.INVALID_SELECTOR);
            return;
        }

        TenantId requestedTenantId = parseTenantId(selectors.getFirst());
        if (requestedTenantId == null) {
            reject(request, response, TenantSelectionRejection.INVALID_SELECTOR);
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
            reject(request, response, TenantSelectionRejection.ACCESS_DENIED);
        }
    }

    /**
     * Audits the denial, then lets the single owning module write its body. A route no responder owns keeps the
     * empty-body error, and so does a route several responders claim: bean order never picks the contract (Issue #222).
     */
    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            TenantSelectionRejection rejection
    ) throws IOException {
        auditRecorder.authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
        List<TenantSelectionRejectionResponder> owners = rejectionResponders.stream()
                .filter(responder -> responder.handles(request)).toList();
        if (owners.size() == 1) {
            owners.getFirst().write(response, rejection);
            return;
        }
        if (owners.size() > 1) {
            log.error("Tenant-selection rejection claimed by more than one responder: {} {} claimed by {}",
                    request.getMethod(), request.getRequestURI(),
                    owners.stream().map(owner -> owner.getClass().getName()).toList());
        }
        response.sendError(rejection.status());
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private TenantId parseTenantId(String selector) {
        try {
            return new TenantId(UUID.fromString(selector));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
