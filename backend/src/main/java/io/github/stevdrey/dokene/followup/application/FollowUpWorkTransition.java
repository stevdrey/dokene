// ABOUTME: What a claim holder's completion or failure did to its follow-up work item (issue 458, ADR 0037). STALE means
// ABOUTME: the claim was no longer current (expired, superseded or already resolved) and nothing was written.
package io.github.stevdrey.dokene.followup.application;

public enum FollowUpWorkTransition {
    COMPLETED,
    RETRY_SCHEDULED,
    EXHAUSTED,
    STALE
}
