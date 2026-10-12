package io.github.stevdrey.dokene.tenant;

import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import java.util.Optional;

/**
 * Tenant rows are RLS-protected by their own ID, so tests read and write them only inside that tenant's scope.
 */
public final class TenantTestSupport {

    private TenantTestSupport() {
    }

    public static Tenant saveTenant(TenantRepository tenants, TenantContextProvider contexts, Tenant tenant) {
        return contexts.callWithTenantId(tenant.id(), () -> tenants.save(tenant));
    }

    public static Optional<Tenant> findTenant(TenantRepository tenants, TenantContextProvider contexts, TenantId tenantId) {
        return contexts.callWithTenantId(tenantId, () -> tenants.findById(tenantId));
    }
}
