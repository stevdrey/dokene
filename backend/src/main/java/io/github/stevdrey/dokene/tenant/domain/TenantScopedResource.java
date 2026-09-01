package io.github.stevdrey.dokene.tenant.domain;

/**
 * Contract for domain entities or resources bounded by a specific tenant.
 */
@FunctionalInterface
public interface TenantScopedResource {

    TenantId tenantId();
}
