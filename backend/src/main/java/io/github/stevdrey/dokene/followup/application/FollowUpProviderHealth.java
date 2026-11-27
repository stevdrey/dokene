// ABOUTME: Port to provider availability monitoring (Phase 4 B1, issue 469) as follow-up work processing reads it
// ABOUTME: (issue 461, ADR 0038): no claim while unavailable, one probe claim while degraded or unknown.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;

/**
 * Implemented by provider availability monitoring (issue 469). The answer is a hint, never an authorization: a holder
 * still re-checks every last-moment gate, and a failure it meets is classified on its own.
 */
public interface FollowUpProviderHealth {

    /** Health states shared with provider availability monitoring (epic 457). */
    enum Availability {
        HEALTHY,
        DEGRADED,
        UNAVAILABLE,
        UNKNOWN
    }

    /** The current availability of the providers the tenant's follow-up work depends on. */
    Availability availability(TenantId tenantId);
}
