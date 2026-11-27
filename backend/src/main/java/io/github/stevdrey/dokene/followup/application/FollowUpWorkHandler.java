// ABOUTME: The work a follow-up claim holder performs (issue 461, ADR 0039), supplied by the scheduler wiring (issue
// ABOUTME: 463). It re-checks every last-moment gate, may renew its lease, and classifies its own attempt.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpWorkAttemptResult;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkClaim;

/**
 * Called outside any tenant scope; a handler binds the claim's tenant itself for the work it does. It must re-evaluate
 * the candidate and every consent, do-not-contact, approval, authorization and kill-switch check (ADR 0028) before any
 * external effect, and must report {@link FollowUpWorkAttemptResult.OutcomeUnknown} whenever it cannot prove that no
 * external effect happened. An exception it throws is recorded as an unknown outcome, never as a retryable failure.
 */
@FunctionalInterface
public interface FollowUpWorkHandler {

    FollowUpWorkAttemptResult handle(Lease lease);

    /** The holder's view of its claim while it works. */
    interface Lease {

        /** The claim as last renewed. */
        FollowUpWorkClaim claim();

        /**
         * Extends the lease by one lease duration from now. {@code false} means the claim is stale: the holder must stop
         * at once and cause no external effect, because another instance may already hold the item.
         */
        boolean renew();

        /**
         * Must be called, and must return {@code true}, immediately before the first operation that may cause an
         * external effect; it also renews the lease. It durably marks the attempt, so the attempt is never retried: a
         * {@code Transient} or {@code Permanent} result is recorded as an unknown outcome, and if the outcome is never
         * recorded (a crash, a failed write), the expired lease sends the item to recovery instead of to another
         * instance. {@code false} means the claim is stale and the holder must cause no effect.
         */
        boolean beginEffect();
    }
}
