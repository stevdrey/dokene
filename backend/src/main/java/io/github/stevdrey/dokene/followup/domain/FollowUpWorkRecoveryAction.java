// ABOUTME: The decisions an authorized member may take on an exhausted follow-up work item (issue 461, ADR 0039).
// ABOUTME: Neither sends anything; a requeue is refused when an external effect may already have happened.
package io.github.stevdrey.dokene.followup.domain;

public enum FollowUpWorkRecoveryAction {
    /**
     * Makes the item claimable again now with a fresh attempt budget. Allowed only when every recorded failure is known
     * to have had no external effect ({@code PROCESSING_ERROR}, {@code DEPENDENCY_UNAVAILABLE}, {@code REJECTED}).
     */
    REQUEUE,
    /**
     * Records the item as handled without another attempt, which releases its customer. This is the only recovery for
     * an unknown outcome: the member resolves the message itself through the Phase 3 send reconciliation (ADR 0030).
     */
    RESOLVE
}
