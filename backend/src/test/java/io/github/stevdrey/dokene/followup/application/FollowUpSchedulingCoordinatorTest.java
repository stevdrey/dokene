// ABOUTME: Pins the follow-up scheduler's orchestration (issue 463, ADR 0040) with fake candidate, ledger, processing,
// ABOUTME: tenant and budget ports: kill switches, bounds, fair rotation, overlap, trusted scope, admission and shutdown.
package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.audit.testsupport.MutableClock;
import io.github.stevdrey.dokene.followup.application.FollowUpSchedulingCoordinator.TickSummary;
import io.github.stevdrey.dokene.followup.application.FollowUpSchedulingCoordinator.TickSummary.Outcome;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkAttemptResult;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.tenant.application.ScopedValueTenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class FollowUpSchedulingCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");
    private static final FollowUpWorkProperties WORK = new FollowUpWorkProperties(20, null, null);

    private final FollowUpCandidateGenerator generator = mock(FollowUpCandidateGenerator.class);
    private final FollowUpWorkLedger ledger = mock(FollowUpWorkLedger.class);
    private final FollowUpWorkProcessor processor = mock(FollowUpWorkProcessor.class);
    private final FollowUpWorkHandler handler = lease -> new FollowUpWorkAttemptResult.Completed();
    private final ScopedValueTenantContextProvider contexts = new ScopedValueTenantContextProvider();
    private final MutableClock clock = new MutableClock(NOW);
    private final List<TenantId> active = new ArrayList<>();
    private final List<Optional<TenantId>> pagesAfter = Collections.synchronizedList(new ArrayList<>());
    private final FollowUpSchedulingTenants tenants = (after, limit) -> {
        pagesAfter.add(after);
        return active.stream().sorted(Comparator.comparing(TenantId::value, FollowUpSchedulingCoordinatorTest::unsigned))
                .filter(tenant -> after.isEmpty() || unsigned(tenant.value(), after.get().value()) > 0)
                .limit(limit).toList();
    };

    @BeforeEach
    void scansAndBatchesSucceed() {
        // Null-safe, because re-stubbing with when(generator.generate(any())) calls this answer with a null tenant.
        when(generator.generate(any())).thenAnswer(invocation -> invocation.getArgument(0) == null ? null
                : new FollowUpCandidateGeneration(invocation.getArgument(0), NOW,
                        FollowUpCandidateGeneration.Outcome.SCANNED, List.of(), false));
        when(processor.process(any(), any(), anyInt(), any())).thenReturn(
                new FollowUpWorkProcessor.RunSummary(FollowUpProviderHealth.Availability.HEALTHY, 2, 2, 0, 0, 0, 0, 0));
    }

    @Test
    void isOffByDefaultAndThenTouchesNothing() {
        tenants(3);
        FollowUpSchedulingCoordinator coordinator = coordinator(properties(null, null, null, 10, 5), null);

        assertThat(coordinator.tick()).isEqualTo(TickSummary.idle(Outcome.DISABLED));
        coordinator.scheduledTick();

        assertThat(pagesAfter).isEmpty();
        verifyNoInteractions(generator, ledger, processor);
    }

    @Test
    void theGenerationKillSwitchStopsScansButKeepsProcessing() {
        tenants(2);

        TickSummary summary = coordinator(properties(true, false, true, 10, 5), null).tick();

        assertThat(summary.tenantsVisited()).isEqualTo(2);
        verifyNoInteractions(generator);
        verify(ledger, org.mockito.Mockito.times(2)).enqueue(any());
        verify(processor, org.mockito.Mockito.times(2)).process(any(), any(), eq(5), eq(handler));
    }

    @Test
    void theProcessingKillSwitchLeavesGenerationAloneAndNeverClaims() {
        tenants(2);

        TickSummary summary = coordinator(properties(true, true, false, 10, 5), null).tick();

        assertThat(summary.tenantsVisited()).isEqualTo(2);
        verify(generator, org.mockito.Mockito.times(2)).generate(any());
        // Generation never dispatches: with processing off, nothing is enqueued, claimed or handed to the handler.
        verifyNoInteractions(ledger, processor);
    }

    @Test
    void bothKillSwitchesOffStillRotatesButDoesNoWork() {
        tenants(2);

        coordinator(properties(true, false, false, 10, 5), null).tick();

        verifyNoInteractions(generator, ledger, processor);
    }

    @Test
    void visitsABoundedPageAndResumesAfterTheLastTenantSoEveryTenantGetsItsTurn() {
        List<TenantId> all = tenants(5);
        FollowUpSchedulingCoordinator coordinator = coordinator(properties(true, true, true, 2, 5), null);
        List<TenantId> visited = new ArrayList<>();
        when(generator.generate(any())).thenAnswer(invocation -> {
            visited.add(invocation.getArgument(0));
            return new FollowUpCandidateGeneration(invocation.getArgument(0), NOW,
                    FollowUpCandidateGeneration.Outcome.SCANNED, List.of(), false);
        });

        assertThat(coordinator.tick().tenantsVisited()).isEqualTo(2);
        assertThat(coordinator.tick().tenantsVisited()).isEqualTo(2);
        assertThat(coordinator.tick().tenantsVisited()).isEqualTo(1);
        // The short page wrapped the rotation back to the first tenant.
        assertThat(coordinator.tick().tenantsVisited()).isEqualTo(2);

        assertThat(visited).containsExactly(all.get(0), all.get(1), all.get(2), all.get(3), all.get(4), all.get(0),
                all.get(1));
        assertThat(pagesAfter).containsExactly(Optional.empty(), Optional.of(all.get(1)), Optional.of(all.get(3)),
                Optional.empty());
    }

    @Test
    void aFailingTenantNeverKeepsTheOthersFromTheirTurn() {
        List<TenantId> all = tenants(3);
        when(generator.generate(all.get(0))).thenThrow(new IllegalStateException("customer text must not be logged"));
        when(ledger.enqueue(all.get(0))).thenThrow(new IllegalStateException("database down"));
        when(processor.process(eq(all.get(0)), any(), anyInt(), any())).thenThrow(new IllegalStateException("x"));
        when(ledger.enqueue(all.get(1))).thenReturn(4);

        TickSummary summary = coordinator(properties(true, true, true, 10, 5), null).tick();

        assertThat(summary.outcome()).isEqualTo(Outcome.RAN);
        assertThat(summary.tenantsVisited()).isEqualTo(3);
        assertThat(summary.failures()).isEqualTo(3);
        assertThat(summary.itemsEnqueued()).isEqualTo(4);
        // The failed tenant's enqueue did not stop its own processing: earlier items may still be claimable.
        verify(processor).process(eq(all.get(0)), any(), eq(5), eq(handler));
        verify(processor).process(eq(all.get(2)), any(), eq(5), eq(handler));
    }

    @Test
    void anInactiveTenantIsNeitherEnqueuedNorProcessed() {
        List<TenantId> all = tenants(1);
        when(generator.generate(all.get(0))).thenReturn(new FollowUpCandidateGeneration(all.get(0), NOW,
                FollowUpCandidateGeneration.Outcome.TENANT_INACTIVE, List.of(), false));

        coordinator(properties(true, true, true, 10, 5), null).tick();

        verifyNoInteractions(ledger, processor);
    }

    @Test
    void anUnreadableTenantPageSkipsTheTick() {
        FollowUpSchedulingTenants failing = (after, limit) -> {
            throw new IllegalStateException("database down");
        };
        FollowUpSchedulingCoordinator coordinator = new FollowUpSchedulingCoordinator(failing, generator, ledger,
                processor, handler, admissions(null), contexts, properties(true, true, true, 10, 5), WORK, clock);

        assertThat(coordinator.tick()).isEqualTo(TickSummary.idle(Outcome.TENANTS_UNAVAILABLE));
        verifyNoInteractions(generator, ledger, processor);
    }

    @Test
    void startsNoNewTenantOnceTheTickBudgetIsSpentAndResumesThereNextTick() {
        List<TenantId> all = tenants(3);
        when(generator.generate(any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(20));
            return new FollowUpCandidateGeneration(invocation.getArgument(0), NOW,
                    FollowUpCandidateGeneration.Outcome.SCANNED, List.of(), false);
        });
        FollowUpSchedulingCoordinator coordinator = coordinator(properties(true, true, true, 10, 5), null);

        TickSummary first = coordinator.tick();

        // 30 second budget: the second tenant started at +20s, the third would start at +40s.
        assertThat(first.tenantsVisited()).isEqualTo(2);
        assertThat(first.cutShort()).isTrue();
        assertThat(coordinator.tick().tenantsVisited()).isEqualTo(1);
        assertThat(pagesAfter).containsExactly(Optional.empty(), Optional.of(all.get(1)));
    }

    @Test
    void theBudgetBoundsTheClaimAndADenialSpendsNoAttempt() {
        List<TenantId> all = tenants(3);
        FollowUpWorkAdmission budget = (tenant, requested) -> tenant.equals(all.get(0)) ? 2
                : tenant.equals(all.get(1)) ? 0 : requested;

        TickSummary summary = coordinator(properties(true, true, true, 10, 5), budget).tick();

        verify(processor).process(eq(all.get(0)), any(), eq(2), eq(handler));
        verify(processor, never()).process(eq(all.get(1)), any(), anyInt(), any());
        verify(processor).process(eq(all.get(2)), any(), eq(5), eq(handler));
        assertThat(summary.tenantsNotAdmitted()).isEqualTo(1);
    }

    @Test
    void anAdmissionFailureOrAnOutOfRangeAnswerAdmitsNothing() {
        List<TenantId> all = tenants(2);
        FollowUpWorkAdmission budget = (tenant, requested) -> {
            if (tenant.equals(all.get(0))) {
                throw new IllegalStateException("budget store down");
            }
            return requested + 1;
        };

        TickSummary summary = coordinator(properties(true, true, true, 10, 5), budget).tick();

        verifyNoInteractions(processor);
        assertThat(summary.tenantsNotAdmitted()).isEqualTo(2);
    }

    @Test
    void countsTenantsThatProviderHealthDeferred() {
        tenants(1);
        when(processor.process(any(), any(), anyInt(), any())).thenReturn(FollowUpWorkProcessor.RunSummary.deferred(
                FollowUpProviderHealth.Availability.UNAVAILABLE));

        assertThat(coordinator(properties(true, true, true, 10, 5), null).tick().tenantsDeferred()).isEqualTo(1);
    }

    @Test
    void claimsAsThisInstancesWorker() {
        tenants(1);
        FollowUpSchedulerProperties properties = new FollowUpSchedulerProperties(true, true, true, null, 10, 5, null,
                null, "pod-a");

        coordinator(properties, null).tick();

        verify(processor).process(any(), eq(new FollowUpWorkerId("pod-a")), eq(5), eq(handler));
    }

    @Test
    void refusesToRunInsideATenantScope() {
        tenants(1);
        FollowUpSchedulingCoordinator coordinator = coordinator(properties(true, true, true, 10, 5), null);

        assertThatThrownBy(() -> contexts.callWithTenantId(TenantId.random(), coordinator::tick))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(generator, ledger, processor);
    }

    @Test
    void aTickThatStartsWhileAnotherRunsDoesNothing() throws Exception {
        tenants(1);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(generator.generate(any())).thenAnswer(invocation -> {
            inside.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new FollowUpCandidateGeneration(invocation.getArgument(0), NOW,
                    FollowUpCandidateGeneration.Outcome.SCANNED, List.of(), false);
        });
        FollowUpSchedulingCoordinator coordinator = coordinator(properties(true, true, true, 10, 5), null);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<TickSummary> first = executor.submit(coordinator::tick);
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(coordinator.tick()).isEqualTo(TickSummary.idle(Outcome.OVERLAPPING));

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).tenantsVisited()).isEqualTo(1);
        }
        verify(generator).generate(any());
        assertThat(coordinator.tick().tenantsVisited()).isEqualTo(1);
    }

    @Test
    void twoInstancesTickIndependentlyAndEachClaimsUnderItsOwnWorker() {
        tenants(2);
        FollowUpSchedulingCoordinator a = coordinator(new FollowUpSchedulerProperties(true, true, true, null, 10, 5,
                null, null, "instance-a"), null);
        FollowUpSchedulingCoordinator b = coordinator(new FollowUpSchedulerProperties(true, true, true, null, 10, 5,
                null, null, "instance-b"), null);

        assertThat(a.tick().tenantsVisited()).isEqualTo(2);
        assertThat(b.tick().tenantsVisited()).isEqualTo(2);

        // Neither instance's tick lock or cursor holds the other back; the database deduplicates their work.
        verify(processor, org.mockito.Mockito.times(2)).process(any(), eq(new FollowUpWorkerId("instance-a")),
                eq(5), eq(handler));
        verify(processor, org.mockito.Mockito.times(2)).process(any(), eq(new FollowUpWorkerId("instance-b")),
                eq(5), eq(handler));
    }

    @Test
    void stoppingWaitsForTheTenantInProgressAndStartsNoOther() throws Exception {
        tenants(3);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(generator.generate(any())).thenAnswer(invocation -> {
            inside.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new FollowUpCandidateGeneration(invocation.getArgument(0), NOW,
                    FollowUpCandidateGeneration.Outcome.SCANNED, List.of(), false);
        });
        FollowUpSchedulingCoordinator coordinator = coordinator(properties(true, true, true, 10, 5), null);
        coordinator.start();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<TickSummary> tick = executor.submit(coordinator::tick);
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> stop = executor.submit((Runnable) () -> coordinator.stop());
            // stop() is waiting for the tick; let the tenant in progress finish.
            Thread.sleep(100);
            assertThat(stop.isDone()).isFalse();
            release.countDown();
            stop.get(10, TimeUnit.SECONDS);

            TickSummary summary = tick.get(10, TimeUnit.SECONDS);
            assertThat(summary.tenantsVisited()).isEqualTo(1);
            assertThat(summary.cutShort()).isTrue();
        }
        assertThat(coordinator.isRunning()).isFalse();
        assertThat(coordinator.tick()).isEqualTo(TickSummary.idle(Outcome.STOPPED));
    }

    @Test
    void stoppingGivesUpWaitingAfterTheShutdownTimeout() throws Exception {
        tenants(1);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(generator.generate(any())).thenAnswer(invocation -> {
            inside.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new FollowUpCandidateGeneration(invocation.getArgument(0), NOW,
                    FollowUpCandidateGeneration.Outcome.SCANNED, List.of(), false);
        });
        FollowUpSchedulingCoordinator coordinator = coordinator(new FollowUpSchedulerProperties(true, true, true,
                null, 10, 5, null, Duration.ofMillis(50), null), null);
        coordinator.start();

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<TickSummary> tick = executor.submit(coordinator::tick);
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            coordinator.stop();
            assertThat(coordinator.isRunning()).isFalse();
            release.countDown();
            tick.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aRestartedSchedulerTicksAgainFromTheFirstTenant() {
        List<TenantId> all = tenants(3);
        FollowUpSchedulingCoordinator coordinator = coordinator(properties(true, true, true, 2, 5), null);
        coordinator.start();
        coordinator.tick();
        coordinator.stop();
        assertThat(coordinator.tick().outcome()).isEqualTo(Outcome.STOPPED);

        coordinator.start();
        assertThat(coordinator.tick().tenantsVisited()).isEqualTo(1);
        // A new instance after a restart keeps no cursor; the database holds every durable state.
        FollowUpSchedulingCoordinator restarted = coordinator(properties(true, true, true, 2, 5), null);
        restarted.start();
        assertThat(restarted.tick().tenantsVisited()).isEqualTo(2);

        assertThat(pagesAfter).containsExactly(Optional.empty(), Optional.of(all.get(1)), Optional.empty());
        coordinator.stop();
        restarted.stop();
    }

    @Test
    void schedulesTicksOnlyWhileEnabledAndStopsThemOnShutdown() {
        FollowUpSchedulingCoordinator disabled = coordinator(properties(false, true, true, 10, 5), null);
        disabled.start();
        assertThat(disabled.ticksScheduled()).isFalse();
        disabled.stop();

        FollowUpSchedulingCoordinator enabled = coordinator(properties(true, true, true, 10, 5), null);
        enabled.start();
        assertThat(enabled.ticksScheduled()).isTrue();
        enabled.stop();
        assertThat(enabled.ticksScheduled()).isFalse();
        assertThat(enabled.isRunning()).isFalse();
    }

    @Test
    void aScheduledTickSurvivesAFailureSoLaterTicksStillRun() {
        FollowUpSchedulingTenants failing = (after, limit) -> {
            throw new AssertionError("an error from a dependency");
        };
        FollowUpSchedulingCoordinator coordinator = new FollowUpSchedulingCoordinator(failing, generator, ledger,
                processor, handler, admissions(null), contexts, properties(true, true, true, 10, 5), WORK, clock);

        coordinator.scheduledTick();
        coordinator.scheduledTick();
        verifyNoInteractions(generator, ledger, processor);
    }


    @Test
    void refusesAWorkBatchLargerThanTheLedgerAccepts() {
        assertThatThrownBy(() -> new FollowUpSchedulingCoordinator(tenants, generator, ledger, processor, handler,
                admissions(null), contexts, properties(true, true, true, 10, 21), WORK, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("work-batch-size");
    }

    @Test
    void aDisabledSchedulerNeverBlocksStartupOverItsWorkBatch() {
        new FollowUpSchedulingCoordinator(tenants, generator, ledger, processor, handler, admissions(null), contexts,
                properties(false, true, true, 10, 21), WORK, clock);
        new FollowUpSchedulingCoordinator(tenants, generator, ledger, processor, handler, admissions(null), contexts,
                properties(true, true, false, 10, 21), WORK, clock);
    }

    @Test
    void thePhaseFourHandlerOnlyRecordsTheCycle() {
        FollowUpWorkHandler.Lease lease = mock(FollowUpWorkHandler.Lease.class);

        assertThat(new RecordOnlyFollowUpWorkHandler().handle(lease))
                .isEqualTo(new FollowUpWorkAttemptResult.Completed());
        // No external effect is begun, so nothing can be sent.
        verifyNoInteractions(lease);
    }

    private List<TenantId> tenants(int count) {
        IntStream.range(0, count).forEach(index -> active.add(TenantId.random()));
        return active.stream().sorted(Comparator.comparing(TenantId::value, FollowUpSchedulingCoordinatorTest::unsigned))
                .toList();
    }

    private FollowUpSchedulingCoordinator coordinator(FollowUpSchedulerProperties properties,
            FollowUpWorkAdmission budget) {
        return new FollowUpSchedulingCoordinator(tenants, generator, ledger, processor, handler, admissions(budget),
                contexts, properties, WORK, clock);
    }

    private static FollowUpSchedulerProperties properties(Boolean enabled, Boolean generation, Boolean processing,
            int tenantsPerTick, int workBatchSize) {
        return new FollowUpSchedulerProperties(enabled, generation, processing, null, tenantsPerTick, workBatchSize,
                null, null, null);
    }

    private static ObjectProvider<FollowUpWorkAdmission> admissions(FollowUpWorkAdmission budget) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (budget != null) {
            beans.addBean("admission", budget);
        }
        return beans.getBeanProvider(FollowUpWorkAdmission.class);
    }

    /** PostgreSQL orders UUIDs by unsigned bytes. */
    private static int unsigned(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
