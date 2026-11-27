// ABOUTME: The durable multi-instance follow-up work ledger (issue 458, ADR 0037): bounded enqueue of candidates, atomic
// ABOUTME: leased claims with a fencing identity, and fenced outcomes. Never approves, dispatches or calls a provider.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpWorkClaim;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkFailure;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkItem;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkState;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every call binds the tenant it is given (or the claim's tenant) with {@link TenantContextProvider#callWithTenantId},
 * must start outside any tenant scope, and runs in one short transaction under forced RLS. Claims and outcomes use the
 * injected clock, so instances must keep their clocks within a small fraction of the lease duration of each other.
 *
 * <p>A claim only records that one worker holds the item. The holder (the retry and dispatch work of issues 461 and
 * 463) re-evaluates the candidate and every last-moment safety gate before it acts, renews the lease while it works,
 * and reports one outcome. A holder whose lease expired, or whose item was claimed again, is stale: its renewal and
 * outcomes are rejected and write nothing, so it can never overwrite a newer attempt.
 */
@Service
@EnableConfigurationProperties(FollowUpWorkProperties.class)
public class FollowUpWorkLedger {
    /** Bounds how many of one customer's older candidates a completion resolves at once. */
    static final int COLLAPSE_LIMIT = 100;
    private static final Logger log = LoggerFactory.getLogger(FollowUpWorkLedger.class);

    private final FollowUpWorkLedgerRepository ledger;
    private final TenantContextProvider contexts;
    private final FollowUpWorkProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public FollowUpWorkLedger(FollowUpWorkLedgerRepository ledger, TenantContextProvider contexts,
            FollowUpWorkProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger);
        this.contexts = Objects.requireNonNull(contexts);
        this.properties = Objects.requireNonNull(properties);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Enqueues up to one batch of the tenant's candidates that have no item yet, one per customer without open work.
     * Candidates that a finished claim already covers are first recorded as collapsed into it, never enqueued.
     *
     * @return how many items were enqueued; a full batch means more candidates may remain
     */
    public int enqueue(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Instant now = clock.instant();
        int enqueued = inTenant(tenantId, () -> {
            ledger.collapseCovered(tenantId, now, properties.batchSize());
            return ledger.enqueue(tenantId, now, properties.batchSize());
        });
        if (enqueued > 0) {
            log.info("Follow-up work enqueued; tenantId={}, enqueued={}", tenantId.value(), enqueued);
        }
        return enqueued;
    }

    /**
     * Claims up to {@code limit} claimable items for {@code worker}. Items whose attempts are spent are exhausted first
     * instead of being claimed again.
     *
     * @throws IllegalArgumentException when {@code limit} is not between 1 and the configured batch size
     */
    public List<FollowUpWorkClaim> claim(TenantId tenantId, FollowUpWorkerId worker, int limit) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(worker, "Worker is required");
        if (limit < 1 || limit > properties.batchSize()) {
            throw new IllegalArgumentException("Claim limit must be between 1 and " + properties.batchSize());
        }
        Instant now = clock.instant();
        Instant leaseExpiresAt = now.plus(properties.leaseDuration());
        int maxAttempts = properties.maxAttempts();
        return inTenant(tenantId, () -> {
            int reconciled = ledger.reconcileCovered(tenantId, now, properties.batchSize());
            if (reconciled > 0) {
                log.info("Covered follow-up work resolved before claiming; tenantId={}, resolved={}",
                        tenantId.value(), reconciled);
            }
            int exhausted = ledger.exhaustSpent(tenantId, now, maxAttempts, properties.batchSize());
            if (exhausted > 0) {
                log.warn("Follow-up work exhausted with its attempts spent; tenantId={}, exhausted={}",
                        tenantId.value(), exhausted);
            }
            return ledger.claim(tenantId, worker, now, leaseExpiresAt, maxAttempts, limit);
        });
    }

    /** Extends a current claim by one lease duration from now; empty when the claim is stale. */
    public Optional<FollowUpWorkClaim> renew(FollowUpWorkClaim claim) {
        Objects.requireNonNull(claim, "Claim is required");
        return inTenant(claim.tenantId(), () -> lockedNow(claim)
                .flatMap(now -> ledger.renew(claim, now, now.plus(properties.leaseDuration()))))
                .map(claim::withLeaseExpiresAt);
    }

    /**
     * Completes a current claim. The customer's older candidates without an item, generated by the claim instant, are
     * resolved together with it rather than becoming separate follow-ups. The collapse records at most
     * {@link #COLLAPSE_LIMIT} of them; enqueue records the rest the same way and never reopens them.
     */
    public FollowUpWorkTransition complete(FollowUpWorkClaim claim) {
        Objects.requireNonNull(claim, "Claim is required");
        return inTenant(claim.tenantId(), () -> {
            Optional<Instant> locked = lockedNow(claim);
            if (locked.isEmpty() || !ledger.complete(claim, locked.get())) {
                return stale(claim, "complete");
            }
            Instant now = locked.get();
            ledger.collapseInto(claim, now, COLLAPSE_LIMIT);
            return FollowUpWorkTransition.COMPLETED;
        });
    }

    /**
     * Records a failed attempt that is safe to retry: no external effect can have happened. The item becomes claimable
     * again at {@code retryAt}, or is exhausted when it has no attempts left; an item exhausted this way does not hold
     * back newer work for its customer.
     *
     * @throws IllegalArgumentException when {@code failure} is {@link FollowUpWorkFailure#OUTCOME_UNKNOWN} or
     *         {@link FollowUpWorkFailure#LEASE_EXPIRED}, or {@code retryAt} is before now
     */
    public FollowUpWorkTransition fail(FollowUpWorkClaim claim, FollowUpWorkFailure failure, Instant retryAt) {
        Objects.requireNonNull(claim, "Claim is required");
        Objects.requireNonNull(failure, "Failure is required");
        Objects.requireNonNull(retryAt, "Retry instant is required");
        if (failure == FollowUpWorkFailure.OUTCOME_UNKNOWN || failure == FollowUpWorkFailure.LEASE_EXPIRED) {
            throw new IllegalArgumentException(failure + " is never retried; require recovery instead");
        }
        if (retryAt.isBefore(clock.instant())) {
            throw new IllegalArgumentException("Retry instant must not be in the past");
        }
        return inTenant(claim.tenantId(), () -> lockedNow(claim)
                .flatMap(now -> ledger.fail(claim, failure, now, retryAt, properties.maxAttempts()))
                .map(state -> state == FollowUpWorkState.EXHAUSTED ? FollowUpWorkTransition.EXHAUSTED
                        : FollowUpWorkTransition.RETRY_SCHEDULED)
                .orElseGet(() -> stale(claim, "fail")));
    }

    /**
     * Marks a current claim's item {@code EXHAUSTED} so it is never claimed again, for example when an external effect
     * may have happened. With {@link FollowUpWorkFailure#OUTCOME_UNKNOWN} or {@link FollowUpWorkFailure#LEASE_EXPIRED},
     * newer work for the customer waits until recovery (issue 461) resolves the item.
     */
    public FollowUpWorkTransition requireRecovery(FollowUpWorkClaim claim, FollowUpWorkFailure failure) {
        Objects.requireNonNull(claim, "Claim is required");
        Objects.requireNonNull(failure, "Failure is required");
        return inTenant(claim.tenantId(), () -> lockedNow(claim)
                .filter(now -> ledger.exhaust(claim, failure, now))
                .map(now -> FollowUpWorkTransition.EXHAUSTED)
                .orElseGet(() -> stale(claim, "require recovery")));
    }

    /** The tenant's oldest {@code limit} items, for diagnostics and tests. */
    public List<FollowUpWorkItem> items(TenantId tenantId, int limit) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        return inTenant(tenantId, () -> ledger.findItems(tenantId, limit));
    }

    /**
     * Locks the current claim's row, then reads the clock: an outcome that waited for another transaction holding the
     * row is judged at the instant it got the lock, not at the instant it started. Empty when the claim is not current
     * or its lease has expired by then.
     */
    private Optional<Instant> lockedNow(FollowUpWorkClaim claim) {
        Optional<Instant> leaseExpiresAt = ledger.lockClaim(claim);
        Instant now = clock.instant();
        return leaseExpiresAt.filter(expiry -> expiry.isAfter(now)).map(expiry -> now);
    }

    private FollowUpWorkTransition stale(FollowUpWorkClaim claim, String action) {
        log.warn("Stale follow-up work claim rejected; tenantId={}, candidateId={}, attempt={}, action={}",
                claim.tenantId().value(), claim.candidateId(), claim.attempt(), action);
        return FollowUpWorkTransition.STALE;
    }

    private <T> T inTenant(TenantId tenantId, Supplier<T> operation) {
        if (contexts.currentTenantId().isPresent()) {
            throw new IllegalStateException("Follow-up work must start without a tenant scope");
        }
        return contexts.callWithTenantId(tenantId, () -> transactions.execute(status -> operation.get()));
    }
}
