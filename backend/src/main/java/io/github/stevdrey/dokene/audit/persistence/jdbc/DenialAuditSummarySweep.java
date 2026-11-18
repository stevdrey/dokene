// ABOUTME: Appends the counts of suppressed authorization denials (spec 0020, ADR 0032): one summary row per ended
// ABOUTME: window on a schedule, the tracker full count first, and whatever is still counted when the backend stops.
package io.github.stevdrey.dokene.audit.persistence.jdbc;

import io.github.stevdrey.dokene.audit.application.DenialAuditBudget;
import io.github.stevdrey.dokene.audit.application.DenialAuditTelemetry;
import io.github.stevdrey.dokene.audit.persistence.jdbc.TransactionalAuditRecorder.SummaryWrite;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DenialAuditSummarySweep {
    /** Keeps one run short on the scheduler thread it shares with the other jobs; a backlog waits for the next run. */
    static final int MAX_ROWS_PER_SWEEP = 500;
    private static final Duration SHUTDOWN_DEADLINE = Duration.ofSeconds(10);
    private static final Logger log = LoggerFactory.getLogger(DenialAuditSummarySweep.class);

    private final DenialAuditBudget budget;
    private final TransactionalAuditRecorder recorder;
    private final DenialAuditTelemetry telemetry;
    /** A scheduled run and the shutdown flush never overlap, so no count is written twice. */
    private final Object writing = new Object();

    DenialAuditSummarySweep(DenialAuditBudget budget, TransactionalAuditRecorder recorder,
            DenialAuditTelemetry telemetry) {
        this.budget = budget;
        this.recorder = recorder;
        this.telemetry = telemetry;
    }

    @Scheduled(fixedDelayString = "${dokene.audit.denial-limit.sweep-interval:PT1M}",
            initialDelayString = "${dokene.audit.denial-limit.sweep-interval:PT1M}")
    void scheduledSweep() {
        sweepOnce();
    }

    /** One run: counts of ended windows, oldest first, after the tracker full count. Stops at the first outage. */
    public void sweepOnce() {
        synchronized (writing) {
            int rows = 0;
            for (DenialAuditBudget.Summary summary : inWriteOrder(budget.drainEnded())) {
                if (rows == MAX_ROWS_PER_SWEEP || !write(summary)) {
                    return;
                }
                rows++;
            }
        }
    }

    /**
     * Saves what a graceful stop would otherwise lose: open windows too, with no row cap but a deadline. No write
     * starts after the deadline; one that started before it may still run to its own transaction timeout. Denials
     * counted while the flush wrote are written too while time is left, and the last look at the tracker puts
     * whatever is still unwritten in the log line.
     */
    @PreDestroy
    void flushOnShutdown() {
        synchronized (writing) {
            long deadline = System.nanoTime() + SHUTDOWN_DEADLINE.toNanos();
            List<DenialAuditBudget.Summary> unwritten = inWriteOrder(budget.drainAll());
            while (!unwritten.isEmpty() && System.nanoTime() - deadline < 0 && write(unwritten.getFirst())) {
                unwritten.removeFirst();
                if (unwritten.isEmpty()) {
                    unwritten = inWriteOrder(budget.drainAll());
                }
            }
            List<DenialAuditBudget.Summary> lost = inWriteOrder(budget.drainAll());
            if (!lost.isEmpty()) {
                log.error("Denial suppression counts lost at shutdown: windows={}, suppressedDenials={}",
                        lost.size(), lost.stream().mapToLong(DenialAuditBudget.Summary::suppressedCount).sum());
            }
        }
    }

    private List<DenialAuditBudget.Summary> inWriteOrder(DenialAuditBudget.Drain drain) {
        List<DenialAuditBudget.Summary> summaries = new ArrayList<>(drain.overflow());
        summaries.addAll(drain.pending());
        return summaries;
    }

    /** False when the database is unavailable: the count stays tracked and the caller stops writing. */
    private boolean write(DenialAuditBudget.Summary summary) {
        SummaryWrite result = recorder.writeSuppressionSummary(summary);
        if (result == SummaryWrite.RETRY) {
            telemetry.summary(DenialAuditTelemetry.SummaryResult.RETRY);
            log.error("Denial suppression summary kept for the next sweep: the database is unavailable");
            return false;
        }
        if (result == SummaryWrite.REJECTED) {
            telemetry.summary(DenialAuditTelemetry.SummaryResult.REJECTED);
            log.error("Denial suppression summary rejected and dropped: reason={}, permission={}, "
                    + "suppressedCount={}", summary.reason(), summary.permission(), summary.suppressedCount());
        } else {
            telemetry.summary(DenialAuditTelemetry.SummaryResult.WRITTEN);
        }
        if (summary.overflow()) {
            budget.overflowFlushed(summary);
        } else {
            budget.flushed(summary);
        }
        return true;
    }
}
