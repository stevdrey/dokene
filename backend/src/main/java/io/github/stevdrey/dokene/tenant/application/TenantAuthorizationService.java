package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;

/**
 * Service for evaluating and enforcing tenant-aware permissions and resource ownership.
 */
public interface TenantAuthorizationService {

    AuthorizationDecision evaluate(TenantPermission permission);

    AuthorizationDecision evaluate(TenantContext context, TenantPermission permission);

    AuthorizationDecision evaluate(TenantContext context, TenantPermission permission, TenantScopedResource resource);

    AuthorizationDecision evaluate(TenantContext context, TenantPermission permission, TenantId resourceTenantId);

    void requirePermission(TenantPermission permission);

    void requireResourceAccess(TenantPermission permission, TenantScopedResource resource);

    void requireResourceAccess(TenantPermission permission, TenantId resourceTenantId);

    boolean hasPermission(TenantPermission permission);

    boolean hasResourceAccess(TenantPermission permission, TenantScopedResource resource);

    boolean hasResourceAccess(TenantPermission permission, TenantId resourceTenantId);
}
