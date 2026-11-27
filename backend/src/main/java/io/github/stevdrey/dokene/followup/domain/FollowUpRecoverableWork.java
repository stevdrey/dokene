// ABOUTME: A read-only view of one exhausted follow-up work item awaiting an operator's recovery decision (issue 461,
// ABOUTME: ADR 0039). Ids, safe categories, counts and instants only: no customer data, worker label or claim token.
package io.github.stevdrey.dokene.followup.domain;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * @param attempts claims made since the item was enqueued, across recoveries
 * @param recoveryCount recovery decisions recorded so far; a recovery names it as its expected version
 * @param lastRecoveryAction the latest recorded decision, empty before the first
 */
public record FollowUpRecoverableWork(UUID candidateId, CustomerId customerId, LocalDate dueDate,
        FollowUpWorkState state, FollowUpWorkFailure lastFailure, int attempts, Optional<Instant> finishedAt,
        int recoveryCount, Optional<FollowUpWorkRecoveryAction> lastRecoveryAction) {

    public FollowUpRecoverableWork {
        Objects.requireNonNull(candidateId, "Candidate id is required");
        Objects.requireNonNull(customerId, "Customer is required");
        Objects.requireNonNull(dueDate, "Due date is required");
        Objects.requireNonNull(state, "State is required");
        Objects.requireNonNull(lastFailure, "Last failure is required");
        Objects.requireNonNull(finishedAt, "Finish instant is required");
        Objects.requireNonNull(lastRecoveryAction, "Last recovery action is required");
        if (recoveryCount < 0 || attempts < 0) {
            throw new IllegalArgumentException("Counts must not be negative");
        }
    }

    /** Whether the outcome of an attempt is unknown, so the item may only be resolved, never requeued. */
    public boolean outcomeUnknown() {
        return lastFailure == FollowUpWorkFailure.OUTCOME_UNKNOWN || lastFailure == FollowUpWorkFailure.LEASE_EXPIRED;
    }

    /** Whether the item still awaits a recovery decision. */
    public boolean awaitingRecovery() {
        return state == FollowUpWorkState.EXHAUSTED;
    }
}
