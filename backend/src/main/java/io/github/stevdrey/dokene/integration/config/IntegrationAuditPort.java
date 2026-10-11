// ABOUTME: Audit port for the integration record; implemented in audit.application like the other durable adapters.
// ABOUTME: Carries the enabled flag and changed field names only, never an identifier, number or reference.
package io.github.stevdrey.dokene.integration.config;

import java.util.Set;
import java.util.UUID;

public interface IntegrationAuditPort {
    /** Requires the save transaction; one INTEGRATION_UPDATED row per successful configuration save. */
    void integrationUpdated(UUID tenantId, boolean enabled, Set<IntegrationField> changedFields);

    /** Requires the save transaction; one OUTBOUND_KILL_SWITCH_CHANGED row per effective switch change. */
    void outboundSwitchChanged(UUID tenantId, boolean enabled);
}
