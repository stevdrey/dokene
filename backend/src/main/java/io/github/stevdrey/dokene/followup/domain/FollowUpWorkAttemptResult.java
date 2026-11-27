// ABOUTME: What one follow-up work attempt reported (issue 461, ADR 0039): completed, a transient or permanent failure
// ABOUTME: known to have had no external effect, or an unknown outcome that must never be replayed blindly.
package io.github.stevdrey.dokene.followup.domain;

import java.util.Objects;
import java.util.Set;

/**
 * The classification a handler gives its attempt. Only {@link Transient} schedules a retry; {@link Permanent} and
 * {@link OutcomeUnknown} exhaust the item at once. A handler that cannot prove that no external effect happened (a send
 * may have reached a provider) must report {@link OutcomeUnknown}; any unexpected exception is treated the same way.
 */
public sealed interface FollowUpWorkAttemptResult {

    /** The attempt finished its work. */
    record Completed() implements FollowUpWorkAttemptResult {
    }

    /** A failure before any external effect that may succeed later: retried with backoff while attempts remain. */
    record Transient(FollowUpWorkFailure failure) implements FollowUpWorkAttemptResult {
        private static final Set<FollowUpWorkFailure> ALLOWED = Set.of(
                FollowUpWorkFailure.PROCESSING_ERROR, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE);

        public Transient {
            Objects.requireNonNull(failure, "Failure is required");
            if (!ALLOWED.contains(failure)) {
                throw new IllegalArgumentException(failure + " is not a transient failure");
            }
        }
    }

    /** A failure before any external effect that retrying unchanged cannot fix: exhausted without a retry. */
    record Permanent(FollowUpWorkFailure failure) implements FollowUpWorkAttemptResult {
        private static final Set<FollowUpWorkFailure> ALLOWED = Set.of(
                FollowUpWorkFailure.PROCESSING_ERROR, FollowUpWorkFailure.REJECTED);

        public Permanent {
            Objects.requireNonNull(failure, "Failure is required");
            if (!ALLOWED.contains(failure)) {
                throw new IllegalArgumentException(failure + " is not a permanent failure");
            }
        }
    }

    /** An external effect may have happened: the item needs recovery and is never retried automatically. */
    record OutcomeUnknown() implements FollowUpWorkAttemptResult {
    }

    static FollowUpWorkAttemptResult completed() {
        return new Completed();
    }

    static FollowUpWorkAttemptResult retryLater(FollowUpWorkFailure failure) {
        return new Transient(failure);
    }

    static FollowUpWorkAttemptResult rejected(FollowUpWorkFailure failure) {
        return new Permanent(failure);
    }

    static FollowUpWorkAttemptResult outcomeUnknown() {
        return new OutcomeUnknown();
    }
}
