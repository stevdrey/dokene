// ABOUTME: Persistence port for the integration row; every call runs under the caller's tenant context and RLS.
// ABOUTME: Writes raise IntegrationRefusedException for the unique and primary key violations by constraint name.
package io.github.stevdrey.dokene.integration.config;

import java.util.Optional;
import java.util.UUID;

public interface TenantMessagingIntegrationRepository {
    /** Plain read, no lock: the gate calls this on every send. */
    Optional<TenantMessagingIntegration> findByTenant(UUID tenantId);

    /** SELECT ... FOR UPDATE, so two saves on the same tenant serialize. */
    Optional<TenantMessagingIntegration> lockByTenant(UUID tenantId);

    /** Version 1. A primary key race maps to STALE_VERSION, the unique indexes to the IN_USE codes. */
    TenantMessagingIntegration insert(TenantMessagingIntegration row);

    /** Optimistic on the expected version; the unique indexes map to the IN_USE codes. */
    TenantMessagingIntegration update(TenantMessagingIntegration expected, TenantMessagingIntegration next);
}
