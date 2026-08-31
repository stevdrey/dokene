package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SpEL expression root for method and web security annotations, e.g. {@code @PreAuthorize("@tenantAuth.hasPermission('CUSTOMER_READ')")}.
 */
@Component("tenantAuth")
public class TenantSecurityExpressions {

    private static final Logger log = LoggerFactory.getLogger(TenantSecurityExpressions.class);

    private final TenantAuthorizationService authorizationService;

    public TenantSecurityExpressions(TenantAuthorizationService authorizationService) {
        this.authorizationService = Objects.requireNonNull(authorizationService, "Tenant authorization service is required");
    }

    public boolean hasPermission(String permissionName) {
        if (permissionName == null) {
            return false;
        }
        try {
            TenantPermission permission = TenantPermission.parse(permissionName);
            return authorizationService.hasPermission(permission);
        } catch (IllegalArgumentException exception) {
            log.warn("Invalid permission name string [{}] passed to SpEL expression. Denying access.", permissionName);
            return false;
        }
    }

    public boolean hasPermission(TenantPermission permission) {
        return authorizationService.hasPermission(permission);
    }

    public boolean hasResourceAccess(TenantScopedResource resource, String permissionName) {
        if (resource == null || permissionName == null) {
            return false;
        }
        try {
            TenantPermission permission = TenantPermission.parse(permissionName);
            return authorizationService.hasResourceAccess(permission, resource);
        } catch (IllegalArgumentException exception) {
            log.warn("Invalid permission name string [{}] passed to SpEL resource access expression. Denying access.", permissionName);
            return false;
        }
    }

    public boolean hasResourceAccess(TenantScopedResource resource, TenantPermission permission) {
        if (resource == null || permission == null) {
            return false;
        }
        return authorizationService.hasResourceAccess(permission, resource);
    }

    public boolean hasResourceAccess(TenantId resourceTenantId, String permissionName) {
        if (resourceTenantId == null || permissionName == null) {
            return false;
        }
        try {
            TenantPermission permission = TenantPermission.parse(permissionName);
            return authorizationService.hasResourceAccess(permission, resourceTenantId);
        } catch (IllegalArgumentException exception) {
            log.warn("Invalid permission name string [{}] passed to SpEL resource tenant access expression. Denying access.", permissionName);
            return false;
        }
    }

    public boolean hasResourceAccess(TenantId resourceTenantId, TenantPermission permission) {
        if (resourceTenantId == null || permission == null) {
            return false;
        }
        return authorizationService.hasResourceAccess(permission, resourceTenantId);
    }
}
