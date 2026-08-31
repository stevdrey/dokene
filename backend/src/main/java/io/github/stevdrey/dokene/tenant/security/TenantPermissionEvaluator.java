package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Spring Security {@link PermissionEvaluator} adapter for tenant-aware permission checks.
 */
@Component
public class TenantPermissionEvaluator implements PermissionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TenantPermissionEvaluator.class);

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

        log.warn(
                "Unsupported target domain object type [{}] evaluated for permission [{}]. Denying access.",
                targetDomainObject.getClass().getName(),
                tenantPermission
        );
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

        log.warn(
                "Unsupported target ID type [{}] or target type [{}] evaluated for permission [{}]. Denying access.",
                targetId.getClass().getName(),
                targetType,
                tenantPermission
        );
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
                log.warn("Invalid permission name string [{}] passed to PermissionEvaluator. Denying access.", stringPermission);
                return null;
            }
        }
        log.warn("Unsupported permission object type [{}] passed to PermissionEvaluator. Denying access.", permission != null ? permission.getClass().getName() : "null");
        return null;
    }
}
