// ABOUTME: Unit tests of the denial summary sweep (spec 0020): one row per ended window, the 500 row cap, the tracker
// ABOUTME: full count first, stop and keep on an outage, drop on a rejection, and the shutdown flush.
package io.github.stevdrey.dokene.audit.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.audit.application.DenialAuditBudget;
import io.github.stevdrey.dokene.audit.application.DenialAuditBudget.Summary;
import io.github.stevdrey.dokene.audit.application.DenialAuditLimitProperties;
import io.github.stevdrey.dokene.audit.application.MicrometerDenialAuditTelemetry;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.audit.persistence.jdbc.TransactionalAuditRecorder.SummaryWrite;
import io.github.stevdrey.dokene.audit.testsupport.MutableClock;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DenialAuditSummarySweepTest {
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));
    private final DenialAuditBudget budget = new DenialAuditBudget(
            new DenialAuditLimitProperties(1, 60, WINDOW, 1_000, Duration.ofMinutes(1)), clock);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TransactionalAuditRecorder recorder = mock(TransactionalAuditRecorder.class);
    private final DenialAuditSummarySweep sweep = new DenialAuditSummarySweep(budget, recorder,
            new MicrometerDenialAuditTelemetry(registry, budget));
    private final List<Summary> written = Collections.synchronizedList(new ArrayList<>());

    /** covers: AC-8 */
    @Test
    void writesNothingForOpenWindowsOrWindowsWithoutACount() {
        deny(new IdentityId(UUID.randomUUID()), 1);
        deny(new IdentityId(UUID.randomUUID()), 3);

        sweep.sweepOnce();
        verifyNoInteractions(recorder);

        recordWrites();
        clock.advance(WINDOW);
        sweep.sweepOnce();
        sweep.sweepOnce();

        assertThat(written).extracting(Summary::suppressedCount).containsExactly(2);
        assertThat(budget.trackedWindows()).isZero();
        assertThat(count("written")).isEqualTo(1);
    }

    /** covers: AC-8 */
    @Test
    void writesAtMostFiveHundredRowsPerRunAndTheBacklogOnTheNext() {
        recordWrites();
        for (int key = 0; key < 600; key++) {
            deny(new IdentityId(UUID.randomUUID()), 2);
        }
        clock.advance(WINDOW);

        sweep.sweepOnce();
        assertThat(written).hasSize(500);
        assertThat(budget.trackedWindows()).isEqualTo(100);

        sweep.sweepOnce();
        assertThat(written).hasSize(600);
        assertThat(written).extracting(Summary::summaryId).doesNotHaveDuplicates();
        assertThat(budget.trackedWindows()).isZero();
    }

    /** covers: AC-9 */
    @Test
    void anOutageStopsTheRunAndEveryCountIsKeptForTheNext(CapturedOutput output) {
        for (int key = 0; key < 3; key++) {
            deny(new IdentityId(UUID.randomUUID()), 2);
            clock.advance(Duration.ofSeconds(1));
        }
        clock.advance(WINDOW);
        List<Summary> attempted = new ArrayList<>();
        when(recorder.writeSuppressionSummary(any())).thenAnswer(call -> {
            attempted.add(call.getArgument(0));
            return SummaryWrite.RETRY;
        });

        sweep.sweepOnce();
        sweep.sweepOnce();

        // Only the oldest count was tried, twice, under the same row id; the later windows were not touched.
        assertThat(attempted).hasSize(2);
        assertThat(attempted.getFirst().summaryId()).isEqualTo(attempted.getLast().summaryId());
        assertThat(budget.trackedWindows()).isEqualTo(3);
        assertThat(count("retry")).isEqualTo(2);
        assertThat(output.getAll()).contains("Denial suppression summary kept for the next sweep");

        recordWrites();
        sweep.sweepOnce();
        assertThat(written).hasSize(3);
        assertThat(written.getFirst().summaryId()).isEqualTo(attempted.getFirst().summaryId());
        assertThat(budget.trackedWindows()).isZero();
    }

    /** covers: AC-9 */
    @Test
    void anAlreadyWrittenCountIsDoneAndIsNotWrittenAgain() {
        deny(new IdentityId(UUID.randomUUID()), 2);
        clock.advance(WINDOW);
        when(recorder.writeSuppressionSummary(any())).thenReturn(SummaryWrite.ALREADY_WRITTEN);

        sweep.sweepOnce();

        assertThat(budget.trackedWindows()).isZero();
        assertThat(count("written")).isEqualTo(1);
    }

    /** covers: AC-9 */
    @Test
    void aRejectedCountIsLoggedDroppedAndTheNextWindowIsStillWritten(CapturedOutput output) {
        deny(new IdentityId(UUID.randomUUID()), 4);
        clock.advance(Duration.ofSeconds(1));
        deny(new IdentityId(UUID.randomUUID()), 2);
        clock.advance(WINDOW);
        when(recorder.writeSuppressionSummary(any())).thenAnswer(call -> {
            Summary summary = call.getArgument(0);
            if (summary.suppressedCount() == 3) {
                return SummaryWrite.REJECTED;
            }
            written.add(summary);
            return SummaryWrite.WRITTEN;
        });

        sweep.sweepOnce();

        assertThat(written).extracting(Summary::suppressedCount).containsExactly(1);
        assertThat(budget.trackedWindows()).isZero();
        assertThat(count("rejected")).isEqualTo(1);
        assertThat(output.getAll()).contains("Denial suppression summary rejected and dropped",
                "reason=NO_TENANT_CONTEXT", "permission=TENANT_READ", "suppressedCount=3");
    }

    /** covers: AC-10 */
    @Test
    void writesTheTrackerFullCountBeforeAnyOtherAndOncePerSweep() {
        recordWrites();
        deny(new IdentityId(UUID.randomUUID()), 2);
        for (int key = 1; key < 1_000; key++) {
            deny(new IdentityId(UUID.randomUUID()), 1);
        }
        deny(new IdentityId(UUID.randomUUID()), 1);
        deny(new IdentityId(UUID.randomUUID()), 1);
        clock.advance(WINDOW);

        sweep.sweepOnce();

        assertThat(written).hasSize(2);
        assertThat(written.getFirst().overflow()).isTrue();
        assertThat(written.getFirst().suppressedCount()).isEqualTo(2);
        assertThat(written.getLast().overflow()).isFalse();

        sweep.sweepOnce();
        assertThat(written).hasSize(2);
    }

    /** covers: AC-8 */
    @Test
    void shutdownWritesOpenWindowsWithACountAndTheOverflow() {
        recordWrites();
        deny(new IdentityId(UUID.randomUUID()), 3);
        for (int key = 1; key < 1_000; key++) {
            deny(new IdentityId(UUID.randomUUID()), 1);
        }
        deny(new IdentityId(UUID.randomUUID()), 1);

        sweep.flushOnShutdown();

        assertThat(written).hasSize(2);
        assertThat(written.getFirst().overflow()).isTrue();
        assertThat(written.getLast().suppressedCount()).isEqualTo(2);
    }

    /** covers: AC-8 */
    @Test
    void shutdownStopsAtAnOutageAndLogsWhatWasLost(CapturedOutput output) {
        deny(new IdentityId(UUID.randomUUID()), 3);
        deny(new IdentityId(UUID.randomUUID()), 5);
        when(recorder.writeSuppressionSummary(any())).thenReturn(SummaryWrite.RETRY);

        sweep.flushOnShutdown();

        assertThat(output.getAll())
                .contains("Denial suppression counts lost at shutdown: windows=2, suppressedDenials=6");
    }

    /** covers: AC-8 */
    @Test
    void shutdownAlsoWritesADenialCountedWhileItWasWriting() {
        IdentityId flooding = new IdentityId(UUID.randomUUID());
        deny(flooding, 3);
        when(recorder.writeSuppressionSummary(any())).thenAnswer(call -> {
            if (written.isEmpty()) {
                // A new window of the same key: one row inside its budget, one denial counted.
                deny(flooding, 2);
            }
            written.add(call.getArgument(0));
            return SummaryWrite.WRITTEN;
        });

        sweep.flushOnShutdown();

        assertThat(written).extracting(Summary::suppressedCount).containsExactly(2, 1);
        assertThat(written).extracting(Summary::summaryId).doesNotHaveDuplicates();
        assertThat(budget.trackedWindows()).isZero();
    }

    /** covers: AC-8 */
    @Test
    void shutdownLogsADenialCountedAfterTheOutageStoppedIt(CapturedOutput output) {
        IdentityId flooding = new IdentityId(UUID.randomUUID());
        deny(flooding, 3);
        when(recorder.writeSuppressionSummary(any())).thenAnswer(call -> {
            deny(new IdentityId(UUID.randomUUID()), 4);
            return SummaryWrite.RETRY;
        });

        sweep.flushOnShutdown();

        assertThat(output.getAll())
                .contains("Denial suppression counts lost at shutdown: windows=2, suppressedDenials=5");
    }

    /** covers: AC-12 */
    @Test
    void aShutdownFlushThatOverlapsARunningSweepWritesEachCountOnce() throws Exception {
        for (int key = 0; key < 20; key++) {
            deny(new IdentityId(UUID.randomUUID()), 2);
        }
        clock.advance(WINDOW);
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(recorder.writeSuppressionSummary(any())).thenAnswer(call -> {
            firstWriteStarted.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            written.add(call.getArgument(0));
            return SummaryWrite.WRITTEN;
        });

        Thread sweeping = Thread.ofPlatform().start(sweep::sweepOnce);
        assertThat(firstWriteStarted.await(30, TimeUnit.SECONDS)).isTrue();
        Thread stopping = Thread.ofPlatform().start(sweep::flushOnShutdown);
        // Hold the sweep inside its first write until the flush waits on the lock. Without the lock the flush would
        // not wait: it would drain the same 20 counts and write them a second time.
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (stopping.getState() != Thread.State.BLOCKED && System.nanoTime() < giveUp) {
            Thread.onSpinWait();
        }
        release.countDown();
        sweeping.join(30_000);
        stopping.join(30_000);

        assertThat(sweeping.isAlive()).isFalse();
        assertThat(stopping.isAlive()).isFalse();
        assertThat(written).hasSize(20);
        assertThat(written).extracting(Summary::summaryId).doesNotHaveDuplicates();
    }

    private void recordWrites() {
        when(recorder.writeSuppressionSummary(any())).thenAnswer(call -> {
            written.add(call.getArgument(0));
            return SummaryWrite.WRITTEN;
        });
    }

    /** The key budget is 1 in this test, so {@code denials} leaves a count of {@code denials - 1}. */
    private void deny(IdentityId identity, int denials) {
        for (int denial = 0; denial < denials; denial++) {
            budget.decide(null, identity, AuditDenialReason.NO_TENANT_CONTEXT, TenantPermission.TENANT_READ,
                    UUID.randomUUID());
        }
    }

    private double count(String result) {
        return registry.get("dokene.audit.denials.summaries").tag("result", result).counter().count();
    }
}
