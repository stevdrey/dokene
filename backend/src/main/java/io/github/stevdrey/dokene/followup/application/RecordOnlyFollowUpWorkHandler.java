// ABOUTME: The Phase 4 follow-up work handler (issue 463, ADR 0040): records that a due cycle was processed and nothing
// ABOUTME: else. It never begins an external effect, so it never approves, creates or sends a message.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpWorkAttemptResult;
import org.springframework.stereotype.Component;

/**
 * Phase 4 automates candidate generation, not judgment: an operator still decides and sends every follow-up from the
 * due queue (ADR 0013), which this handler does not change. Completing the item only closes the cycle in the work
 * ledger, collapsing the customer's older candidates into it (ADR 0037). Automated sending is Phase 5 and will replace
 * this handler with one that calls {@code beginEffect} and re-checks every last-moment gate (ADR 0028) first.
 */
@Component
public class RecordOnlyFollowUpWorkHandler implements FollowUpWorkHandler {

    @Override
    public FollowUpWorkAttemptResult handle(Lease lease) {
        return new FollowUpWorkAttemptResult.Completed();
    }
}
