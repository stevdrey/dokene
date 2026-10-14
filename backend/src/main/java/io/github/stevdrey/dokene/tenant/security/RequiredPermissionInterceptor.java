package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.tenant.application.RequiredPermission;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Evaluates {@link RequiredPermission} before argument resolution. Denials are audited and surfaced as
 * {@code TenantAccessDeniedException}, which the controller exception handlers map to 403.
 */
public class RequiredPermissionInterceptor implements HandlerInterceptor {
    private final TenantAuthorizationService authorization;

    public RequiredPermissionInterceptor(TenantAuthorizationService authorization) {
        this.authorization = authorization;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            RequiredPermission required = method.getMethodAnnotation(RequiredPermission.class);
            if (required != null && required.anyOf()) {
                TenantPermission[] permissions = required.value();
                if (permissions.length == 0) {
                    throw new IllegalStateException("anyOf requires at least one permission");
                }
                boolean admitted = false;
                for (TenantPermission permission : permissions) {
                    admitted = admitted || authorization.evaluate(permission).isAllowed();
                }
                if (!admitted) {
                    // requirePermission denies and audits; the last listed permission names the denial.
                    authorization.requirePermission(permissions[permissions.length - 1]);
                }
            } else if (required != null) {
                for (TenantPermission permission : required.value()) {
                    authorization.requirePermission(permission);
                }
            }
        }
        return true;
    }
}
