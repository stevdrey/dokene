package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Spring Security {@link PermissionEvaluator} adapter for tenant-aware permission checks.
 */
@Component
public class TenantPermissionEvaluator implements PermissionEvaluator {

    private final TenantAuthorizationService authorizationService;

    public TenantPermissionEvaluator(TenantAuthorizationService authorizationService) {
        this.authorizationService = Objects.requireNonNull(authorizationService, "Tenant authorization service is required");
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        TenantPermission tenantPermission = parsePermission(permission);
        if (tenantPermission == null) {
            return false;
        }

        if (targetDomainObject == null) {
            return authorizationService.hasPermission(tenantPermission);
        }
        if (targetDomainObject instanceof TenantScopedResource resource) {
            return authorizationService.hasResourceAccess(tenantPermission, resource);
        }
        if (targetDomainObject instanceof TenantId resourceTenantId) {
            return authorizationService.hasResourceAccess(tenantPermission, resourceTenantId);
        }
        return false;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        TenantPermission tenantPermission = parsePermission(permission);
        if (tenantPermission == null || targetId == null) {
            return false;
        }

        if (targetId instanceof UUID uuid && ("Tenant".equalsIgnoreCase(targetType) || "TenantId".equalsIgnoreCase(targetType))) {
            return authorizationService.hasResourceAccess(tenantPermission, new TenantId(uuid));
        }

        return false;
    }

    private TenantPermission parsePermission(Object permission) {
        if (permission instanceof TenantPermission tenantPermission) {
            return tenantPermission;
        }
        if (permission instanceof String stringPermission) {
            try {
                return TenantPermission.parse(stringPermission);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
        return null;
    }
}
