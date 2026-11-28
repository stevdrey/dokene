// ABOUTME: Port to the rate and abuse budget (Phase 4 B2, issue 459) as the follow-up scheduler reads it (issue 463,
// ABOUTME: ADR 0040): how many work items a tenant may claim this tick. A denial is not a failure of the work.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;

/**
 * Implemented by the budget policy adapter (issue 459). The scheduler asks once per tenant and tick before it claims,
 * so a denied tenant spends no attempt. The answer only bounds how much work is claimed; it never authorizes an
 * external effect, and every last-moment gate still applies inside the handler.
 */
@FunctionalInterface
public interface FollowUpWorkAdmission {

    /**
     * How many of {@code requested} work items {@code tenantId} may claim now, between 0 and {@code requested}. The
     * scheduler treats any other answer, or an exception, as 0 for this tick.
     */
    int admit(TenantId tenantId, int requested);
}
