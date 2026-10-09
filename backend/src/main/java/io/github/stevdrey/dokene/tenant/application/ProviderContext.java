// ABOUTME: Trusted context for the provider webhook path: a tenant with no member behind it.
// ABOUTME: Distinct from TenantContext, which always carries a membership.
package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;

public record ProviderContext(TenantId tenantId) {
    public ProviderContext {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
    }
}
