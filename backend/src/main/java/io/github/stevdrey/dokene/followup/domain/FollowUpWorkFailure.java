// ABOUTME: Safe failure categories a follow-up work attempt may record (issue 458, ADR 0037). They carry no provider
// ABOUTME: payload, message content or personal data, so they are safe for logs and later metrics.
package io.github.stevdrey.dokene.followup.domain;

public enum FollowUpWorkFailure {
    /** The attempt failed inside Dokene before any external effect. */
    PROCESSING_ERROR,
    /** A dependency was unavailable before any external effect. */
    DEPENDENCY_UNAVAILABLE,
    /**
     * The attempt was refused definitively before any external effect (issue 461, ADR 0038): retrying it unchanged
     * cannot succeed, so the item is exhausted at once and only an operator's recovery may requeue it.
     */
    REJECTED,
    /** The claimant's lease expired on its last allowed attempt, so its outcome is unknown to the ledger. */
    LEASE_EXPIRED,
    /** An external effect may have happened; the item must never be replayed blindly. */
    OUTCOME_UNKNOWN
}
