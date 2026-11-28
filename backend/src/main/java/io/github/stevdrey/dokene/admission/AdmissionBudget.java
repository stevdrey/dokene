// ABOUTME: The one port that decides whether a tenant may start a send or admit a follow up candidate right now
// ABOUTME: (ADR 0040). Every instance counts against the same PostgreSQL state; a denial is a value, never an exception.
package io.github.stevdrey.dokene.admission;

import io.github.stevdrey.dokene.tenant.domain.TenantId;

/**
 * Caller contract for both admissions: an active read write transaction at {@code READ COMMITTED}, the tenant of the
 * argument in the tenant context, one tenant per transaction, and no provider call before that transaction commits.
 * An admission that is granted counts only if the caller commits; one that is denied counts nowhere.
 */
public interface AdmissionBudget {

    /**
     * Counts one send against the tenant minute, tenant day and global minute limits.
     *
     * @throws IllegalStateException when no transaction is active or the transaction is read only
     */
    AdmissionDecision admitSend(TenantId tenant);

    /**
     * Counts one follow up candidate against the tenant's daily candidate budget. Send limits, the outbound switches
     * and the tenant pause play no part.
     *
     * @throws IllegalStateException when no transaction is active or the transaction is read only
     */
    AdmissionDecision admitCandidate(TenantId tenant);

    /**
     * Pauses the tenant's sends after the provider throttled it. Call it with the tenant in context, after the
     * provider answered and outside the claim transaction: it runs in a transaction of its own. Never throws; a
     * signal that cannot be stored is logged and counted.
     */
    void recordProviderThrottle(TenantId tenant);
}
