package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextAuthorizationException;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
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

    TenantContextRequestFilter(
            TenantContextProvider tenantContexts,
            TenantContextResolver tenantContextResolver,
            AuthenticatedTenantIdentityResolver identityResolver,
            RequestMatcher tenantScopedRequestMatcher
    ) {
        this.tenantContexts = tenantContexts;
        this.tenantContextResolver = tenantContextResolver;
        this.identityResolver = identityResolver;
        this.tenantScopedRequestMatcher = tenantScopedRequestMatcher;
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
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (selectors.size() != 1) {
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
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }
    }
}
