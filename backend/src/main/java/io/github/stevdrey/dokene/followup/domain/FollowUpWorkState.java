// ABOUTME: States of a follow-up work item in the durable work ledger (issue 458, ADR 0037). A claimed item is held by
// ABOUTME: one worker under a lease; completed and exhausted items are final for the ledger.
package io.github.stevdrey.dokene.followup.domain;

public enum FollowUpWorkState {
    /** Enqueued and claimable from its {@code availableAt} instant. */
    PENDING,
    /** Held by one worker until its lease expires; an expired claim may be claimed again as a new attempt. */
    CLAIMED,
    /** Done, by its own claim or collapsed into another candidate of the same customer. */
    COMPLETED,
    /** A failed attempt that may be claimed again from its {@code availableAt} instant. */
    RETRY_DUE,
    /** Out of attempts or needing an operator decision (recovery required); never claimed again by the ledger. */
    EXHAUSTED
}
