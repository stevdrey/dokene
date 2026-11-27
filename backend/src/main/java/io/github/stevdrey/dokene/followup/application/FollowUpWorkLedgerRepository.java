// ABOUTME: Persistence port for the durable follow-up work ledger (issue 458, ADR 0037): bounded enqueue and claim,
// ABOUTME: and fenced lease renewal and outcomes. Every method runs in the caller's transaction under a tenant scope.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpWorkClaim;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkFailure;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkItem;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkState;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The fenced methods match the item on its candidate id, state {@code CLAIMED}, the claim's attempt number and token,
 * and a lease that has not expired at {@code now}; when any of these differ they write nothing.
 */
public interface FollowUpWorkLedgerRepository {

    /**
     * Adds a {@code PENDING} item for at most {@code limit} customers that have candidates without an item and no open
     * item, taking each customer's latest due cycle, in {@code dueDate, customerId} order. A candidate that the database
     * recorded before the latest claim of one of its customer's finished (completed or exhausted) items is covered by
     * it and never enqueued. An item exhausted with an unknown outcome keeps its customer open; one exhausted by safe
     * failures does not. Nothing is added when the tenant is not active.
     *
     * @return how many items were added
     */
    int enqueue(TenantId tenantId, Instant now, int limit);

    /**
     * Records at most {@code limit} covered candidates that have no item yet as {@code COMPLETED}, collapsed into the
     * latest finished item that covers them, so every candidate eventually has a ledger row.
     *
     * @return how many candidates were recorded
     *
     */
    int collapseCovered(TenantId tenantId, Instant now, int limit);

    /**
     * Marks {@code EXHAUSTED} at most {@code limit} items that may not be claimed again: expired claims and due retries
     * that already used {@code maxAttempts}.
     *
     * @return how many items were exhausted
     */
    int exhaustSpent(TenantId tenantId, Instant now, int maxAttempts, int limit);

    /**
     * Atomically claims at most {@code limit} claimable items (pending or retry due at {@code now}, or claimed with an
     * expired lease) that have attempts left, in {@code dueDate, customerId} order, skipping rows another transaction
     * holds. Nothing is claimed when the tenant is not active.
     */
    List<FollowUpWorkClaim> claim(TenantId tenantId, FollowUpWorkerId worker, Instant now, Instant leaseExpiresAt,
            int maxAttempts, int limit);

    /** Extends the current claim's lease to {@code leaseExpiresAt}, never shortening it; empty when stale. */
    Optional<Instant> renew(FollowUpWorkClaim claim, Instant now, Instant leaseExpiresAt);

    /** Completes the current claim's item; {@code false} when stale. */
    boolean complete(FollowUpWorkClaim claim, Instant now);

    /**
     * Records the claim's customer's candidates that have no item yet and that the database recorded by the time it
     * recorded the claim (read from the item, not from {@code claim}) as completed together with the claim's candidate,
     * at most {@code limit} of them.
     */
    int collapseInto(FollowUpWorkClaim claim, Instant now, int limit);

    /**
     * Records a failed attempt: {@code RETRY_DUE} from {@code retryAt} while attempts remain, otherwise
     * {@code EXHAUSTED}; empty when stale.
     */
    Optional<FollowUpWorkState> fail(FollowUpWorkClaim claim, FollowUpWorkFailure failure, Instant now,
            Instant retryAt, int maxAttempts);

    /** Marks the current claim's item {@code EXHAUSTED} for recovery; {@code false} when stale. */
    boolean exhaust(FollowUpWorkClaim claim, FollowUpWorkFailure failure, Instant now);

    /**
     * The oldest {@code limit} of the tenant's items in {@code dueDate, customerId, candidateId} order, for diagnostics
     * and tests.
     *
     * @throws IllegalArgumentException when {@code limit} is not positive
     */
    List<FollowUpWorkItem> findItems(TenantId tenantId, int limit);
}
