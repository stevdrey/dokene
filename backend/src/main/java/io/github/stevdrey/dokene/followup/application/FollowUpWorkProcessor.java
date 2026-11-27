// ABOUTME: Processes one bounded batch of a tenant's follow-up work (issue 461, ADR 0039): reads provider health,
// ABOUTME: claims, runs the handler, classifies its attempt and records a bounded retry, exhaustion or recovery.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpWorkAttemptResult;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkClaim;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkFailure;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The scheduler wiring (issue 463) calls {@link #process} outside any tenant scope, with bounded limits and its own
 * fair tenant rotation; this class schedules nothing and sends nothing itself.
 *
 * <ul>
 *   <li>Provider health (issue 469) gates the claim: {@code UNAVAILABLE} claims nothing, so no attempt is spent during
 *       an outage; {@code DEGRADED} or {@code UNKNOWN} (also when no health monitor is wired) claims one item as a
 *       probe.</li>
 *   <li>A transient failure is retried after a capped, jittered backoff while attempts remain; once a handler reports
 *       {@code DEPENDENCY_UNAVAILABLE}, the batch's remaining claims are released the same way without being
 *       attempted.</li>
 *   <li>A permanent failure exhausts the item at once. An unknown outcome, including any exception the handler throws,
 *       exhausts it for recovery and holds back newer work for its customer: it is never replayed automatically.</li>
 *   <li>Each claim's lease is renewed just before its handler starts; a claim that expired or was taken over while it
 *       waited in the batch is never handed to the handler. A stale claim records nothing; the newer holder owns the
 *       item.</li>
 *   <li>When a renewal or an outcome write fails, or a handler throws an {@link Error}, the claims not yet handed to a
 *       handler are released untried. An {@code Error} records an unknown outcome for its claim first and is then
 *       rethrown. A claim whose write cannot be made is left to its lease and counted as {@code abandoned}.</li>
 * </ul>
 */
@Service
public class FollowUpWorkProcessor {
    private static final Logger log = LoggerFactory.getLogger(FollowUpWorkProcessor.class);

    private final FollowUpWorkLedger ledger;
    private final FollowUpRetryPolicy retries;
    private final ObjectProvider<FollowUpProviderHealth> health;
    private final Clock clock;

    public FollowUpWorkProcessor(FollowUpWorkLedger ledger, FollowUpRetryPolicy retries,
            ObjectProvider<FollowUpProviderHealth> health, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger);
        this.retries = Objects.requireNonNull(retries);
        this.health = Objects.requireNonNull(health);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Counts of what one batch did; safe for logs and metrics (no ids or personal data). */
    public record RunSummary(FollowUpProviderHealth.Availability availability, int claimed, int completed,
            int retryScheduled, int exhausted, int recoveryRequired, int stale, int abandoned) {

        static RunSummary deferred(FollowUpProviderHealth.Availability availability) {
            return new RunSummary(availability, 0, 0, 0, 0, 0, 0, 0);
        }

        /** Whether provider health kept the batch from claiming anything. */
        public boolean deferred() {
            return availability == FollowUpProviderHealth.Availability.UNAVAILABLE;
        }
    }

    /**
     * Claims at most {@code limit} items for {@code worker} (one while health is degraded or unknown) and works each.
     *
     * @throws IllegalArgumentException when {@code limit} is outside the ledger's batch bounds
     */
    public RunSummary process(TenantId tenantId, FollowUpWorkerId worker, int limit, FollowUpWorkHandler handler) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(worker, "Worker is required");
        Objects.requireNonNull(handler, "Handler is required");
        FollowUpProviderHealth.Availability availability = availability(tenantId);
        if (availability == FollowUpProviderHealth.Availability.UNAVAILABLE) {
            log.info("Follow-up work deferred while providers are unavailable; tenantId={}", tenantId.value());
            return RunSummary.deferred(availability);
        }
        int batch = availability == FollowUpProviderHealth.Availability.HEALTHY ? limit : Math.min(limit, 1);
        List<FollowUpWorkClaim> claims = ledger.claim(tenantId, worker, batch);
        Tally tally = new Tally();
        boolean dependencyDown = false;
        for (int index = 0; index < claims.size(); index++) {
            FollowUpWorkClaim claim = claims.get(index);
            if (dependencyDown) {
                releaseUntried(claim, tally);
                continue;
            }
            HeldLease lease = new HeldLease(claim);
            // Claims wait in this loop while earlier handlers run: renew each one just before its handler starts, and
            // never start a handler for a claim that expired or that another instance took over meanwhile.
            boolean held;
            try {
                held = lease.renew();
            } catch (RuntimeException failure) {
                // No handler started, so nothing external happened: release this claim and the rest of the batch as a
                // safe transient failure instead of leaving them to expire as unknown outcomes.
                log.warn("Follow-up work claim could not be renewed before its handler; releasing the batch untried; "
                        + "tenantId={}, candidateId={}, attempt={}, exceptionClass={}", claim.tenantId().value(),
                        claim.candidateId(), claim.attempt(), failure.getClass().getName());
                dependencyDown = true;
                releaseUntried(claim, tally);
                continue;
            }
            if (!held) {
                tally.add(FollowUpWorkTransition.STALE, false);
                continue;
            }
            FollowUpWorkAttemptResult result;
            try {
                result = attempt(handler, lease);
            } catch (Error fatal) {
                // The handler may have acted: never replay it. Record an unknown outcome, spare the untried claims,
                // then let the error end the run.
                recordUnknownOutcome(lease.claim(), tally);
                for (FollowUpWorkClaim untried : claims.subList(index + 1, claims.size())) {
                    releaseUntried(untried, tally);
                }
                throw fatal;
            }
            try {
                tally.add(record(lease.claim(), result), result instanceof FollowUpWorkAttemptResult.OutcomeUnknown);
            } catch (RuntimeException failure) {
                // The handler ran, so this claim is left to its lease; the claims after it never started.
                log.warn("Follow-up work outcome could not be recorded; releasing the rest of the batch untried; "
                        + "tenantId={}, candidateId={}, attempt={}, exceptionClass={}", claim.tenantId().value(),
                        claim.candidateId(), claim.attempt(), failure.getClass().getName());
                tally.abandoned++;
                dependencyDown = true;
                continue;
            }
            dependencyDown = result instanceof FollowUpWorkAttemptResult.Transient(var failure)
                    && failure == FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE;
        }
        RunSummary summary = new RunSummary(availability, claims.size(), tally.completed, tally.retryScheduled,
                tally.exhausted, tally.recoveryRequired, tally.stale, tally.abandoned);
        if (!claims.isEmpty()) {
            log.info("Follow-up work batch processed; tenantId={}, availability={}, claimed={}, completed={}, "
                    + "retryScheduled={}, exhausted={}, recoveryRequired={}, stale={}, abandoned={}", tenantId.value(),
                    availability, summary.claimed(), summary.completed(), summary.retryScheduled(),
                    summary.exhausted(), summary.recoveryRequired(), summary.stale(), summary.abandoned());
        }
        return summary;
    }

    private FollowUpProviderHealth.Availability availability(TenantId tenantId) {
        FollowUpProviderHealth monitor = health.getIfAvailable();
        if (monitor == null) {
            return FollowUpProviderHealth.Availability.UNKNOWN;
        }
        try {
            return Optional.ofNullable(monitor.availability(tenantId))
                    .orElse(FollowUpProviderHealth.Availability.UNKNOWN);
        } catch (RuntimeException failure) {
            log.warn("Provider health could not be read; treating it as unknown; exceptionClass={}",
                    failure.getClass().getName());
            return FollowUpProviderHealth.Availability.UNKNOWN;
        }
    }

    /**
     * Releases a claim whose handler never started: nothing external happened, so it is a safe transient failure that
     * spares the dependency until the backoff passes. A release that cannot be written leaves the claim to its lease.
     */
    private void releaseUntried(FollowUpWorkClaim claim, Tally tally) {
        try {
            tally.add(ledger.fail(claim, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE,
                    retries.retryAt(clock.instant(), claim.attempt())), false);
        } catch (RuntimeException failure) {
            log.warn("Untried follow-up work claim could not be released; leaving it to lease expiry; tenantId={}, "
                    + "candidateId={}, attempt={}, exceptionClass={}", claim.tenantId().value(), claim.candidateId(),
                    claim.attempt(), failure.getClass().getName());
            tally.abandoned++;
        }
    }

    /** Best effort after a handler {@link Error}: a claim whose outcome cannot be written is left to its lease. */
    private void recordUnknownOutcome(FollowUpWorkClaim claim, Tally tally) {
        try {
            tally.add(ledger.requireRecovery(claim, FollowUpWorkFailure.OUTCOME_UNKNOWN), true);
        } catch (RuntimeException failure) {
            log.warn("Unknown follow-up work outcome could not be recorded; leaving it to lease expiry; tenantId={}, "
                    + "candidateId={}, attempt={}, exceptionClass={}", claim.tenantId().value(), claim.candidateId(),
                    claim.attempt(), failure.getClass().getName());
            tally.abandoned++;
        }
    }

    private FollowUpWorkAttemptResult attempt(FollowUpWorkHandler handler, HeldLease lease) {
        try {
            return Objects.requireNonNull(handler.handle(lease), "Handler returned no result");
        } catch (RuntimeException failure) {
            // The handler cannot prove that nothing external happened, so the item is never replayed blindly. The
            // message may carry customer or provider text and is not logged.
            log.warn("Follow-up work handler failed; recording an unknown outcome; tenantId={}, candidateId={}, "
                    + "attempt={}, exceptionClass={}", lease.claim().tenantId().value(), lease.claim().candidateId(),
                    lease.claim().attempt(), failure.getClass().getName());
            return new FollowUpWorkAttemptResult.OutcomeUnknown();
        }
    }

    private FollowUpWorkTransition record(FollowUpWorkClaim claim, FollowUpWorkAttemptResult result) {
        return switch (result) {
            case FollowUpWorkAttemptResult.Completed() -> ledger.complete(claim);
            case FollowUpWorkAttemptResult.Transient(var failure) ->
                    ledger.fail(claim, failure, retries.retryAt(clock.instant(), claim.attempt()));
            case FollowUpWorkAttemptResult.Permanent(var failure) -> ledger.requireRecovery(claim, failure);
            case FollowUpWorkAttemptResult.OutcomeUnknown() ->
                    ledger.requireRecovery(claim, FollowUpWorkFailure.OUTCOME_UNKNOWN);
        };
    }

    private final class HeldLease implements FollowUpWorkHandler.Lease {
        private FollowUpWorkClaim claim;
        private boolean stale;

        private HeldLease(FollowUpWorkClaim claim) {
            this.claim = claim;
        }

        @Override
        public FollowUpWorkClaim claim() {
            return claim;
        }

        @Override
        public boolean renew() {
            if (stale) {
                return false;
            }
            Optional<FollowUpWorkClaim> renewed = ledger.renew(claim);
            renewed.ifPresentOrElse(current -> claim = current, () -> stale = true);
            return renewed.isPresent();
        }
    }

    private static final class Tally {
        private int completed;
        private int retryScheduled;
        private int exhausted;
        private int recoveryRequired;
        private int stale;
        private int abandoned;

        private void add(FollowUpWorkTransition transition, boolean outcomeUnknown) {
            switch (transition) {
                case COMPLETED -> completed++;
                case RETRY_SCHEDULED -> retryScheduled++;
                case EXHAUSTED -> {
                    if (outcomeUnknown) {
                        recoveryRequired++;
                    } else {
                        exhausted++;
                    }
                }
                case STALE -> stale++;
            }
        }
    }
}
