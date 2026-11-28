// ABOUTME: The recurring follow-up scheduler (issue 463, ADR 0040): disabled by default, each tick visits a bounded,
// ABOUTME: rotating page of active tenants and scans, enqueues and processes bounded work for each. Never sends.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

/**
 * Composes candidate generation (ADR 0036), the work ledger (ADR 0037) and work processing (ADR 0039) into one
 * recurring job. It owns orchestration only: correctness under several instances comes from the database (deduplicated
 * candidates, one open item per customer, fenced claims), so no leader election or advisory lock is needed.
 *
 * <ul>
 *   <li><b>Off by default.</b> {@code dokene.follow-up.scheduler.enabled} is the master switch; generation and
 *       processing have their own switches beneath it.</li>
 *   <li><b>Bounded.</b> A tick visits at most {@code tenants-per-tick} tenants, makes one candidate scan, one enqueue
 *       and one processing batch of at most {@code work-batch-size} items per tenant, and starts no new tenant once
 *       {@code max-tick-duration} has passed.</li>
 *   <li><b>Fair.</b> Tenants are visited in id order after a cursor that resumes after the last tenant visited, so a
 *       large or failing tenant never keeps the others from their turn. A short page wraps to the first tenant.</li>
 *   <li><b>Trusted context.</b> A tick must start outside any tenant scope. Tenant ids come only from the signed
 *       maintenance finder (V51); every call it makes binds that tenant under forced RLS.</li>
 *   <li><b>Own thread.</b> Ticks run with a fixed delay on a thread of their own, started only while enabled, so a
 *       long tick never delays the application's other scheduled jobs.</li>
 *   <li><b>One tick at a time.</b> A tick that starts while another runs in this instance does nothing.</li>
 *   <li><b>Graceful shutdown.</b> Stopping starts no new tenant and waits up to {@code shutdown-timeout} for the
 *       tenant in progress. Anything cut short is recovered by lease expiry (ADR 0037, ADR 0039).</li>
 * </ul>
 *
 * <p>Nothing here approves, creates or sends a message. The handler decides what processing an item means; in Phase 4
 * it only records the cycle ({@link RecordOnlyFollowUpWorkHandler}).
 */
@Service
@EnableConfigurationProperties(FollowUpSchedulerProperties.class)
public class FollowUpSchedulingCoordinator implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(FollowUpSchedulingCoordinator.class);

    private final FollowUpSchedulingTenants tenants;
    private final FollowUpCandidateGenerator generator;
    private final FollowUpWorkLedger ledger;
    private final FollowUpWorkProcessor processor;
    private final FollowUpWorkHandler handler;
    private final ObjectProvider<FollowUpWorkAdmission> admission;
    private final TenantContextProvider contexts;
    private final FollowUpSchedulerProperties properties;
    private final FollowUpWorkerId worker;
    private final Clock clock;
    private final ReentrantLock tickLock = new ReentrantLock();
    private final AtomicReference<TenantId> cursor = new AtomicReference<>();
    private volatile boolean running;
    private volatile boolean stopping;
    /** Counts stops, so a tick that outlived a timed-out stop never resumes after a later start. */
    private final AtomicLong stops = new AtomicLong();
    private ScheduledExecutorService ticks;

    public FollowUpSchedulingCoordinator(FollowUpSchedulingTenants tenants, FollowUpCandidateGenerator generator,
            FollowUpWorkLedger ledger, FollowUpWorkProcessor processor, FollowUpWorkHandler handler,
            ObjectProvider<FollowUpWorkAdmission> admission, TenantContextProvider contexts,
            FollowUpSchedulerProperties properties, FollowUpWorkProperties work, Clock clock) {
        this.tenants = Objects.requireNonNull(tenants);
        this.generator = Objects.requireNonNull(generator);
        this.ledger = Objects.requireNonNull(ledger);
        this.processor = Objects.requireNonNull(processor);
        this.handler = Objects.requireNonNull(handler);
        this.admission = Objects.requireNonNull(admission);
        this.contexts = Objects.requireNonNull(contexts);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.worker = properties.worker();
        if (properties.processingActive() && properties.workBatchSize() > work.batchSize()) {
            // The ledger would refuse a larger claim on every tick; fail at startup instead. Checked only while
            // processing can run, so a disabled scheduler never blocks startup.
            throw new IllegalStateException("dokene.follow-up.scheduler.work-batch-size (" + properties.workBatchSize()
                    + ") must not exceed dokene.follow-up.work.batch-size (" + work.batchSize() + ")");
        }
        if (properties.enabled()) {
            log.info("Follow-up scheduler enabled; workerId={}, generation={}, processing={}, tenantsPerTick={}, "
                    + "workBatchSize={}", worker.value(), properties.generationActive(),
                    properties.processingActive(), properties.tenantsPerTick(), properties.workBatchSize());
        }
    }

    /** What one tick did; counts only, safe for logs and metrics. */
    public record TickSummary(Outcome outcome, int tenantsVisited, int candidatesRecorded, int itemsEnqueued,
            int itemsClaimed, int itemsCompleted, int tenantsDeferred, int tenantsNotAdmitted, int failures,
            boolean cutShort) {

        public enum Outcome {
            /** The tick visited its page of tenants (possibly none). */
            RAN,
            /** The master switch, or both stage switches, are off: nothing was read or written. */
            DISABLED,
            /** The scheduler is stopping or stopped: nothing was read or written. */
            STOPPED,
            /** Another tick of this instance was still running: nothing was read or written. */
            OVERLAPPING,
            /** The tenant page could not be read, so no tenant was visited. */
            TENANTS_UNAVAILABLE
        }

        static TickSummary idle(Outcome outcome) {
            return new TickSummary(outcome, 0, 0, 0, 0, 0, 0, 0, 0, false);
        }
    }

    /**
     * A tick on the scheduler's own thread. A throwable escaping it would silently cancel every later tick, so only a
     * virtual machine error other than a stack overflow does, and only after it is logged; the processor has already
     * recorded an unknown outcome for a handler's error.
     */
    void scheduledTick() {
        try {
            tick();
        } catch (VirtualMachineError fatal) {
            if (!(fatal instanceof StackOverflowError)) {
                log.error("Follow-up scheduler stopped by a virtual machine error; no later tick runs until restart; "
                        + "exceptionClass={}", fatal.getClass().getName());
                throw fatal;
            }
            log.error("Follow-up scheduler tick failed; exceptionClass={}", fatal.getClass().getName());
        } catch (RuntimeException | Error exception) {
            log.error("Follow-up scheduler tick failed; exceptionClass={}", exception.getClass().getName());
        }
    }

    /**
     * Runs one bounded tick now.
     *
     * @throws IllegalStateException when called inside a tenant scope
     */
    public TickSummary tick() {
        if (!properties.generationActive() && !properties.processingActive()) {
            // Neither stage would do anything, so no tenant is listed and no maintenance capability is signed.
            return TickSummary.idle(TickSummary.Outcome.DISABLED);
        }
        if (contexts.currentTenantId().isPresent()) {
            throw new IllegalStateException("The follow-up scheduler must start without a tenant scope");
        }
        long stopsAtStart = stops.get();
        if (stopping) {
            return TickSummary.idle(TickSummary.Outcome.STOPPED);
        }
        if (!tickLock.tryLock()) {
            log.debug("Follow-up scheduler tick skipped; the previous tick is still running");
            return TickSummary.idle(TickSummary.Outcome.OVERLAPPING);
        }
        try {
            return stopping || stops.get() != stopsAtStart ? TickSummary.idle(TickSummary.Outcome.STOPPED)
                    : runTick(stopsAtStart);
        } finally {
            tickLock.unlock();
        }
    }

    private TickSummary runTick(long stopsAtStart) {
        Instant deadline = clock.instant().plus(properties.maxTickDuration());
        List<TenantId> page;
        try {
            page = tenants.activeTenantsAfter(Optional.ofNullable(cursor.get()), properties.tenantsPerTick());
        } catch (RuntimeException failure) {
            log.warn("Follow-up scheduler could not list tenants; tick skipped; exceptionClass={}",
                    failure.getClass().getName());
            return TickSummary.idle(TickSummary.Outcome.TENANTS_UNAVAILABLE);
        }
        Tally tally = new Tally();
        boolean cutShort = false;
        for (TenantId tenantId : page) {
            if (stopping || stops.get() != stopsAtStart || !clock.instant().isBefore(deadline)) {
                cutShort = true;
                break;
            }
            // Advanced before the visit, so even a tenant whose visit throws an Error gives way to the next one.
            cursor.set(tenantId);
            visit(tenantId, tally);
        }
        if (!cutShort && page.size() < properties.tenantsPerTick()) {
            // The rotation reached the last tenant: the next tick starts again from the first.
            cursor.set(null);
        }
        TickSummary summary = new TickSummary(TickSummary.Outcome.RAN, tally.visited, tally.recorded, tally.enqueued,
                tally.claimed, tally.completed, tally.deferred, tally.notAdmitted, tally.failures, cutShort);
        if (tally.recorded + tally.enqueued + tally.claimed + tally.failures > 0 || cutShort) {
            log.info("Follow-up scheduler tick; workerId={}, tenantsVisited={}, candidatesRecorded={}, "
                    + "itemsEnqueued={}, itemsClaimed={}, itemsCompleted={}, tenantsDeferred={}, "
                    + "tenantsNotAdmitted={}, failures={}, cutShort={}", worker.value(), summary.tenantsVisited(),
                    summary.candidatesRecorded(), summary.itemsEnqueued(), summary.itemsClaimed(),
                    summary.itemsCompleted(), summary.tenantsDeferred(), summary.tenantsNotAdmitted(),
                    summary.failures(), summary.cutShort());
        }
        return summary;
    }

    /** Each stage fails on its own: a failure is counted and logged by class, and the next stage still runs. */
    private void visit(TenantId tenantId, Tally tally) {
        tally.visited++;
        if (properties.generationActive()) {
            try {
                FollowUpCandidateGeneration generation = generator.generate(tenantId);
                if (generation.outcome() == FollowUpCandidateGeneration.Outcome.TENANT_INACTIVE) {
                    // Suspended or archived since the page was read: the ledger would refuse it as well.
                    return;
                }
                tally.recorded += generation.recorded().size();
            } catch (RuntimeException failure) {
                tally.failed(tenantId, "generation", failure);
            }
        }
        if (!properties.processingActive()) {
            return;
        }
        try {
            tally.enqueued += ledger.enqueue(tenantId);
        } catch (RuntimeException failure) {
            // Items enqueued earlier may still be claimable, so processing still runs.
            tally.failed(tenantId, "enqueue", failure);
        }
        int admitted = admitted(tenantId);
        if (admitted == 0) {
            tally.notAdmitted++;
            return;
        }
        try {
            FollowUpWorkProcessor.RunSummary run = processor.process(tenantId, worker, admitted, handler);
            tally.claimed += run.claimed();
            tally.completed += run.completed();
            if (run.deferred()) {
                tally.deferred++;
            }
        } catch (RuntimeException failure) {
            tally.failed(tenantId, "processing", failure);
        }
    }

    /** Fails closed: an answer outside {@code 0..requested}, or an exception, admits nothing this tick. */
    private int admitted(TenantId tenantId) {
        int requested = properties.workBatchSize();
        FollowUpWorkAdmission budget = admission.getIfAvailable();
        if (budget == null) {
            return requested;
        }
        try {
            int granted = budget.admit(tenantId, requested);
            if (granted < 0 || granted > requested) {
                log.warn("Follow-up work admission answered out of range; admitting nothing; tenantId={}",
                        tenantId.value());
                return 0;
            }
            return granted;
        } catch (RuntimeException failure) {
            log.warn("Follow-up work admission failed; admitting nothing; tenantId={}, exceptionClass={}",
                    tenantId.value(), failure.getClass().getName());
            return 0;
        }
    }

    /** Starts the fixed-delay ticks on a thread of their own, only while the master switch is on. */
    @Override
    public synchronized void start() {
        stopping = false;
        running = true;
        if (properties.enabled() && ticks == null) {
            ticks = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("follow-up-scheduler")
                    .daemon(true).factory());
            long interval = properties.interval().toMillis();
            ticks.scheduleWithFixedDelay(this::scheduledTick, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Schedules no further tick, starts no new tenant and waits up to the shutdown timeout for a running tick; a tick
     * still running after that is left to finish its tenant on its own thread, never interrupted, and its claims to
     * their leases.
     */
    @Override
    public synchronized void stop() {
        stopping = true;
        stops.incrementAndGet();
        if (ticks != null) {
            ticks.shutdown();
            ticks = null;
        }
        try {
            if (tickLock.tryLock(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                tickLock.unlock();
            } else {
                log.warn("Follow-up scheduler tick still running at shutdown; leaving its claims to lease expiry");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Whether fixed-delay ticks are scheduled on the scheduler's own thread. */
    synchronized boolean ticksScheduled() {
        return ticks != null;
    }

    private static final class Tally {
        private int visited;
        private int recorded;
        private int enqueued;
        private int claimed;
        private int completed;
        private int deferred;
        private int notAdmitted;
        private int failures;

        private void failed(TenantId tenantId, String stage, RuntimeException failure) {
            failures++;
            // The message may carry SQL or customer text and is not logged.
            log.warn("Follow-up scheduler stage failed; continuing; tenantId={}, stage={}, exceptionClass={}",
                    tenantId.value(), stage, failure.getClass().getName());
        }
    }
}
