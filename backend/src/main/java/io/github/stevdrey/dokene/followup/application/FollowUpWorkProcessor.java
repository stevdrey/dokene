// ABOUTME: Processes one bounded batch of a tenant's follow-up work (issue 461, ADR 0038): reads provider health,
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
            int retryScheduled, int exhausted, int recoveryRequired, int stale) {

        static RunSummary deferred(FollowUpProviderHealth.Availability availability) {
            return new RunSummary(availability, 0, 0, 0, 0, 0, 0);
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
        for (FollowUpWorkClaim claim : claims) {
            if (dependencyDown) {
                // Not attempted, so nothing external happened; spare the dependency until the backoff passes.
                tally.add(ledger.fail(claim, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE,
                        retries.retryAt(clock.instant(), claim.attempt())), false);
                continue;
            }
            HeldLease lease = new HeldLease(claim);
            // Claims wait in this loop while earlier handlers run: renew each one just before its handler starts, and
            // never start a handler for a claim that expired or that another instance took over meanwhile.
            if (!lease.renew()) {
                tally.add(FollowUpWorkTransition.STALE, false);
                continue;
            }
            FollowUpWorkAttemptResult result = attempt(handler, lease);
            tally.add(record(lease.claim(), result), result instanceof FollowUpWorkAttemptResult.OutcomeUnknown);
            dependencyDown = result instanceof FollowUpWorkAttemptResult.Transient(var failure)
                    && failure == FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE;
        }
        RunSummary summary = new RunSummary(availability, claims.size(), tally.completed, tally.retryScheduled,
                tally.exhausted, tally.recoveryRequired, tally.stale);
        if (!claims.isEmpty()) {
            log.info("Follow-up work batch processed; tenantId={}, availability={}, claimed={}, completed={}, "
                    + "retryScheduled={}, exhausted={}, recoveryRequired={}, stale={}", tenantId.value(),
                    availability, summary.claimed(), summary.completed(), summary.retryScheduled(),
                    summary.exhausted(), summary.recoveryRequired(), summary.stale());
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
