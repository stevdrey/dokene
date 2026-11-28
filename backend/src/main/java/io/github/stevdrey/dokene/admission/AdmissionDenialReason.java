// ABOUTME: The closed set of reasons an admission is denied (ADR 0040). The reason stays in counters and logs; a
// ABOUTME: tenant facing answer must not show it, so no tenant learns about other tenants' traffic.
package io.github.stevdrey.dokene.admission;

public enum AdmissionDenialReason {
    /** An outbound switch or the integration state blocks sending, or candidate admission is not enabled. */
    SWITCH_OFF,
    /** The provider throttled this tenant a moment ago. */
    TENANT_PAUSED,
    /** The tenant's own per minute limit. */
    TENANT_MINUTE,
    /** The tenant's share of the global per minute limit, when that share is the smaller limit. */
    TENANT_SHARE,
    TENANT_DAY,
    GLOBAL_MINUTE,
    CANDIDATE_DAY,
    /** The budget state could not be read, written or locked in time. Fails closed. */
    STATE_UNAVAILABLE
}
