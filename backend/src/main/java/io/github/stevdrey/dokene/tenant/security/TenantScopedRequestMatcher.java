package io.github.stevdrey.dokene.tenant.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Matches HTTP requests that operate within an explicit tenant boundary.
 *
 * <p>Authenticated global endpoints (such as tenant discovery or user account management)
 * are excluded from tenant context enforcement.</p>
 */
public class TenantScopedRequestMatcher implements RequestMatcher {

    private static final RequestMatcher DEFAULT_GLOBAL_ENDPOINTS = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern("/api/tenants"),
            PathPatternRequestMatcher.pathPattern("/api/tenants/**"),
            PathPatternRequestMatcher.pathPattern("/api/account/**"),
            PathPatternRequestMatcher.pathPattern("/api/profile/**"),
            PathPatternRequestMatcher.pathPattern("/api/me/**")
    );

    private final RequestMatcher tenantScopeMatcher;

    public TenantScopedRequestMatcher() {
        this(DEFAULT_GLOBAL_ENDPOINTS);
    }

    public TenantScopedRequestMatcher(RequestMatcher globalEndpointsMatcher) {
        Objects.requireNonNull(globalEndpointsMatcher, "Global endpoints matcher is required");
        this.tenantScopeMatcher = request -> !globalEndpointsMatcher.matches(request);
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        return tenantScopeMatcher.matches(request);
    }
}
