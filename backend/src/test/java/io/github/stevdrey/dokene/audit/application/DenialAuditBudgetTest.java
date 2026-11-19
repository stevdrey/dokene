// ABOUTME: Unit tests of the denial audit budget on a fixed clock (spec 0020): key budget, fixed windows, identity
// ABOUTME: ceiling, tenant separation, the shared slot, capacity and overflow, pending counts and concurrency.
package io.github.stevdrey.dokene.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.audit.application.DenialAuditBudget.Cause;
import io.github.stevdrey.dokene.audit.application.DenialAuditBudget.Decision;
import io.github.stevdrey.dokene.audit.application.DenialAuditBudget.Drain;
import io.github.stevdrey.dokene.audit.application.DenialAuditBudget.Summary;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.audit.testsupport.MutableClock;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DenialAuditBudgetTest {
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int CAPACITY = 1_000;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));
    private final DenialAuditBudget budget = new DenialAuditBudget(
            new DenialAuditLimitProperties(10, 60, WINDOW, CAPACITY, Duration.ofMinutes(1)), clock);
    private final IdentityId identity = new IdentityId(UUID.randomUUID());

    /** covers: AC-1 */
    @Test
    void writesTheFirstTenDenialsOfAKeyAndCountsTheRest() {
        List<Decision> decisions = new ArrayList<>();
        for (int denial = 0; denial < 13; denial++) {
            decisions.add(unattributed(identity));
        }

        assertThat(decisions.subList(0, 10)).allMatch(decision -> !decision.suppressed());
        assertThat(decisions.subList(10, 13)).extracting(Decision::cause).containsOnly(Cause.KEY_BUDGET);
        assertThat(decisions.subList(10, 13)).extracting(Decision::first).containsExactly(true, false, false);
        assertThat(decisions).extracting(Decision::identity).containsOnly(identity);
    }

    /** covers: AC-1, AC-8 */
    @Test
    void aWindowWithoutSuppressedDenialsLeavesNoCount() {
        for (int denial = 0; denial < 3; denial++) {
            assertThat(unattributed(identity).suppressed()).isFalse();
        }
        clock.advance(WINDOW);

        Drain drain = budget.drainEnded();

        assertThat(drain.pending()).isEmpty();
        assertThat(drain.overflow()).isEmpty();
        assertThat(budget.trackedWindows()).isZero();
    }

    /** covers: AC-2 */
    @Test
    void theCountCarriesTheCorrelationOfTheFirstSuppressedDenial() {
        UUID eleventh = null;
        for (int denial = 1; denial <= 1_000; denial++) {
            UUID correlation = UUID.randomUUID();
            if (denial == 11) {
                eleventh = correlation;
            }
            budget.decide(null, identity, AuditDenialReason.NO_TENANT_CONTEXT, TenantPermission.TENANT_READ,
                    correlation);
        }
        clock.advance(WINDOW);

        Summary summary = budget.drainEnded().pending().getFirst();

        assertThat(summary.suppressedCount()).isEqualTo(990);
        assertThat(summary.correlationId()).isEqualTo(eleventh);
        assertThat(summary.context()).isNull();
        assertThat(summary.permission()).isEqualTo(TenantPermission.TENANT_READ);
        assertThat(summary.reason()).isEqualTo(AuditDenialReason.NO_TENANT_CONTEXT);
        assertThat(summary.overflow()).isFalse();
    }

    /** covers: AC-6, AC-8 */
    @Test
    void aWindowEndsExactlyAtItsEndAndTheNextDenialOpensAFullOne() {
        flood(null, identity, 12);
        clock.advance(WINDOW.minusNanos(1));
        assertThat(unattributed(identity).cause()).isEqualTo(Cause.KEY_BUDGET);

        clock.advance(Duration.ofNanos(1));
        Decision reopened = unattributed(identity);

        assertThat(reopened.suppressed()).isFalse();
        List<Summary> pending = budget.drainEnded().pending();
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().suppressedCount()).isEqualTo(3);
        budget.flushed(pending.getFirst());
        assertThat(budget.drainEnded().pending()).isEmpty();
        assertThat(budget.trackedWindows()).isEqualTo(1);
    }

    /** covers: AC-3, AC-7 */
    @Test
    void theSameIdentityHasASeparateBudgetAndCountInEachTenant() {
        TenantContext first = member(identity);
        TenantContext second = member(identity);
        flood(first, null, 15);
        flood(second, null, 12);
        clock.advance(WINDOW);

        List<Summary> pending = budget.drainEnded().pending();

        assertThat(pending).hasSize(2);
        assertThat(pending).filteredOn(summary -> summary.context().equals(first))
                .extracting(Summary::suppressedCount).containsExactly(5);
        assertThat(pending).filteredOn(summary -> summary.context().equals(second))
                .extracting(Summary::suppressedCount).containsExactly(2);
    }

    /** covers: AC-3 */
    @Test
    void theTenantContextBeatsTheIdentityTheCallerPassed() {
        TenantContext context = member(identity);

        Decision decision = budget.decide(context, new IdentityId(UUID.randomUUID()),
                AuditDenialReason.INSUFFICIENT_PERMISSION, TenantPermission.CUSTOMER_WRITE, UUID.randomUUID());

        assertThat(decision.identity()).isEqualTo(identity);
    }

    /** covers: AC-4, AC-5 */
    @Test
    void anIdentityWritesAtMostSixtyRowsAcrossItsKeysAndTheRestCountUnderTheirOwnKey() {
        TenantPermission[] permissions = {TenantPermission.TENANT_READ, TenantPermission.TENANT_UPDATE,
                TenantPermission.CUSTOMER_READ, TenantPermission.CUSTOMER_WRITE, TenantPermission.PURCHASE_READ,
                TenantPermission.PURCHASE_WRITE, TenantPermission.AUDIT_READ};
        List<Decision> decisions = new ArrayList<>();
        for (TenantPermission permission : permissions) {
            for (int denial = 0; denial < 10; denial++) {
                decisions.add(budget.decide(null, identity, AuditDenialReason.NO_TENANT_CONTEXT, permission,
                        UUID.randomUUID()));
            }
        }

        assertThat(decisions.subList(0, 60)).allMatch(decision -> !decision.suppressed());
        assertThat(decisions.subList(60, 70)).extracting(Decision::cause).containsOnly(Cause.IDENTITY_CEILING);
        clock.advance(WINDOW);
        List<Summary> pending = budget.drainEnded().pending();
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().permission()).isEqualTo(TenantPermission.AUDIT_READ);
        assertThat(pending.getFirst().suppressedCount()).isEqualTo(10);
    }

    /** covers: AC-5 */
    @Test
    void aSpentKeyLeavesOtherKeysAndOtherIdentitiesTheirBudget() {
        flood(null, identity, 20);

        Decision otherKey = budget.decide(null, identity, AuditDenialReason.NO_TENANT_CONTEXT,
                TenantPermission.AUDIT_READ, UUID.randomUUID());
        Decision otherIdentity = unattributed(new IdentityId(UUID.randomUUID()));

        assertThat(otherKey.suppressed()).isFalse();
        assertThat(otherIdentity.suppressed()).isFalse();
    }

    /** covers: AC-4, AC-6 */
    @Test
    void theIdentityWindowIsFixedFromItsFirstDenial() {
        TenantPermission[] permissions = {TenantPermission.TENANT_READ, TenantPermission.TENANT_UPDATE,
                TenantPermission.CUSTOMER_READ, TenantPermission.CUSTOMER_WRITE, TenantPermission.PURCHASE_READ,
                TenantPermission.PURCHASE_WRITE};
        for (TenantPermission permission : permissions) {
            for (int denial = 0; denial < 10; denial++) {
                budget.decide(null, identity, AuditDenialReason.NO_TENANT_CONTEXT, permission, UUID.randomUUID());
            }
        }
        Decision overCeiling = budget.decide(null, identity, AuditDenialReason.NO_TENANT_CONTEXT,
                TenantPermission.AUDIT_READ, UUID.randomUUID());
        clock.advance(WINDOW);

        Decision afterWindow = budget.decide(null, identity, AuditDenialReason.NO_TENANT_CONTEXT,
                TenantPermission.MEMBERSHIP_READ, UUID.randomUUID());

        assertThat(overCeiling.cause()).isEqualTo(Cause.IDENTITY_CEILING);
        assertThat(afterWindow.suppressed()).isFalse();
    }

    /** covers: AC-11 */
    @Test
    void denialsWithoutAnyIdentityShareOneSlot() {
        List<Decision> decisions = new ArrayList<>();
        for (int denial = 0; denial < 11; denial++) {
            decisions.add(budget.decide(null, null, AuditDenialReason.NO_TENANT_CONTEXT, TenantPermission.AUDIT_READ,
                    UUID.randomUUID()));
        }

        assertThat(decisions.subList(0, 10)).allMatch(decision -> !decision.suppressed());
        assertThat(decisions.getLast().cause()).isEqualTo(Cause.KEY_BUDGET);
        assertThat(decisions.getLast().identity()).isNull();
        assertThat(unattributed(identity).suppressed()).isFalse();
    }

    /** covers: AC-16 */
    @Test
    void aDenialWithoutAPermissionHasItsOwnKeyAndCount() {
        for (int denial = 0; denial < 12; denial++) {
            budget.decide(null, identity, AuditDenialReason.NO_TENANT_CONTEXT, null, UUID.randomUUID());
        }
        clock.advance(WINDOW);

        Summary summary = budget.drainEnded().pending().getFirst();

        assertThat(summary.permission()).isNull();
        assertThat(summary.reason()).isEqualTo(AuditDenialReason.NO_TENANT_CONTEXT);
        assertThat(summary.suppressedCount()).isEqualTo(2);
        assertThat(summary.overflow()).isFalse();
    }

    /** covers: AC-6, AC-10, AC-13 */
    @Test
    void aFullTrackerCountsNewKeysAsOverflowAndKeepsServingOpenWindows() {
        IdentityId tracked = fillTracker();
        UUID firstOverflow = UUID.randomUUID();

        Decision first = budget.decide(null, new IdentityId(UUID.randomUUID()), AuditDenialReason.NO_TENANT_CONTEXT,
                TenantPermission.TENANT_READ, firstOverflow);
        Decision second = unattributed(new IdentityId(UUID.randomUUID()));
        Decision openWindow = unattributed(tracked);

        assertThat(first).extracting(Decision::cause, Decision::first).containsExactly(Cause.TRACKER_FULL, true);
        assertThat(second).extracting(Decision::cause, Decision::first).containsExactly(Cause.TRACKER_FULL, false);
        assertThat(openWindow.suppressed()).isFalse();
        assertThat(budget.trackedWindows()).isEqualTo(CAPACITY);

        Drain drain = budget.drainEnded();
        assertThat(drain.overflow()).hasSize(1);
        Summary overflow = drain.overflow().getFirst();
        assertThat(overflow.overflow()).isTrue();
        assertThat(overflow.suppressedCount()).isEqualTo(2);
        assertThat(overflow.correlationId()).isEqualTo(firstOverflow);
        assertThat(overflow.context()).isNull();
        assertThat(overflow.permission()).isNull();
        assertThat(overflow.reason()).isNull();

        // The first overflow after a sweep is reported again, in a new count with a new row id.
        Decision afterSweep = unattributed(new IdentityId(UUID.randomUUID()));
        assertThat(afterSweep).extracting(Decision::cause, Decision::first)
                .containsExactly(Cause.TRACKER_FULL, true);
        // The sealed count is not replaced until it was written.
        assertThat(budget.drainEnded().overflow()).containsExactly(overflow);
        budget.overflowFlushed(overflow);
        List<Summary> next = budget.drainEnded().overflow();
        assertThat(next).hasSize(1);
        assertThat(next.getFirst().suppressedCount()).isEqualTo(1);
        assertThat(next.getFirst().summaryId()).isNotEqualTo(overflow.summaryId());
    }

    /** covers: AC-6, AC-9, AC-10 */
    @Test
    void countsWaitingToBeWrittenStillOccupyTheTracker() {
        for (int key = 0; key < CAPACITY; key++) {
            flood(null, new IdentityId(UUID.randomUUID()), 11);
        }
        IdentityId ended = new IdentityId(UUID.randomUUID());
        clock.advance(WINDOW);

        // Every window has ended with a count, so all of them are pending and a key that reopens overflows.
        List<Summary> pending = budget.drainEnded().pending();
        Decision whileFull = unattributed(ended);
        budget.flushed(pending.getFirst());
        Decision afterOneFlush = unattributed(ended);

        assertThat(pending).hasSize(CAPACITY);
        assertThat(whileFull.cause()).isEqualTo(Cause.TRACKER_FULL);
        assertThat(afterOneFlush.suppressed()).isFalse();
        assertThat(budget.trackedWindows()).isEqualTo(CAPACITY);
    }

    /** covers: AC-8 */
    @Test
    void pendingCountsComeOldestFirstAndEndedWindowsOfOneKeyStaySeparate() {
        IdentityId later = new IdentityId(UUID.randomUUID());
        flood(null, identity, 11);
        clock.advance(Duration.ofMinutes(5));
        flood(null, later, 12);
        clock.advance(WINDOW);
        flood(null, identity, 14);
        clock.advance(WINDOW);

        List<Summary> pending = budget.drainEnded().pending();

        assertThat(pending).extracting(Summary::suppressedCount).containsExactly(1, 2, 4);
        assertThat(pending).extracting(Summary::summaryId).doesNotHaveDuplicates();
    }

    /** covers: AC-8 */
    @Test
    void shutdownAlsoDrainsOpenWindowsAndTheOverflowCountedSinceTheLastSweep() {
        flood(null, fillTracker(), 11);
        unattributed(new IdentityId(UUID.randomUUID()));
        Summary sealed = budget.drainEnded().overflow().getFirst();
        unattributed(new IdentityId(UUID.randomUUID()));
        unattributed(new IdentityId(UUID.randomUUID()));

        Drain drain = budget.drainAll();

        assertThat(drain.overflow()).extracting(Summary::suppressedCount).containsExactly(1, 2);
        assertThat(drain.overflow().getFirst()).isEqualTo(sealed);
        assertThat(drain.pending()).hasSize(1);
        drain.overflow().forEach(budget::overflowFlushed);
        assertThat(budget.drainAll().overflow()).isEmpty();
    }

    /** covers: AC-8 */
    @Test
    void overflowCountedWhileShutdownWritesTheOpenCountIsKeptAsItsOwnCount() {
        fillTracker();
        unattributed(new IdentityId(UUID.randomUUID()));
        unattributed(new IdentityId(UUID.randomUUID()));
        // An earlier count is still waiting, so shutdown gets the newer one unsealed.
        Summary waiting = budget.drainEnded().overflow().getFirst();
        unattributed(new IdentityId(UUID.randomUUID()));
        unattributed(new IdentityId(UUID.randomUUID()));
        Summary beingWritten = budget.drainAll().overflow().getLast();
        assertThat(beingWritten.summaryId()).isNotEqualTo(waiting.summaryId());
        assertThat(beingWritten.suppressedCount()).isEqualTo(2);

        Decision late = unattributed(new IdentityId(UUID.randomUUID()));
        unattributed(new IdentityId(UUID.randomUUID()));
        unattributed(new IdentityId(UUID.randomUUID()));
        budget.overflowFlushed(beingWritten);

        assertThat(late.first()).isFalse();
        List<Summary> rest = budget.drainAll().overflow();
        assertThat(rest).extracting(Summary::suppressedCount).containsExactly(2, 3);
        assertThat(rest.getFirst()).isEqualTo(waiting);
        assertThat(rest.getLast().summaryId()).isNotEqualTo(beingWritten.summaryId());
        assertThat(rest.getLast().correlationId()).isEqualTo(beingWritten.correlationId());
    }

    /** covers: AC-12 */
    @Test
    void oneHundredConcurrentDenialsOfAFreshKeyWriteExactlyTen() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Decision>> decisions = new ArrayList<>();
        try (ExecutorService threads = Executors.newFixedThreadPool(100)) {
            for (int denial = 0; denial < 100; denial++) {
                decisions.add(threads.submit(() -> {
                    start.await();
                    return unattributed(identity);
                }));
            }
            start.countDown();
            threads.shutdown();
            assertThat(threads.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        int written = 0;
        for (Future<Decision> decision : decisions) {
            if (!decision.get().suppressed()) {
                written++;
            }
        }
        clock.advance(WINDOW);
        assertThat(written).isEqualTo(10);
        assertThat(budget.drainEnded().pending().getFirst().suppressedCount()).isEqualTo(90);
    }

    /** Opens one window in every slot of the tracker; the returned identity owns one that has budget left. */
    private IdentityId fillTracker() {
        IdentityId tracked = new IdentityId(UUID.randomUUID());
        unattributed(tracked);
        for (int key = 1; key < CAPACITY; key++) {
            unattributed(new IdentityId(UUID.randomUUID()));
        }
        return tracked;
    }

    private Decision unattributed(IdentityId who) {
        return budget.decide(null, who, AuditDenialReason.NO_TENANT_CONTEXT, TenantPermission.TENANT_READ,
                UUID.randomUUID());
    }

    private void flood(TenantContext context, IdentityId who, int denials) {
        for (int denial = 0; denial < denials; denial++) {
            budget.decide(context, who, context == null ? AuditDenialReason.NO_TENANT_CONTEXT
                    : AuditDenialReason.INSUFFICIENT_PERMISSION, context == null ? TenantPermission.TENANT_READ
                    : TenantPermission.CUSTOMER_WRITE, UUID.randomUUID());
        }
    }

    private static TenantContext member(IdentityId who) {
        return new TenantContext(TenantId.random(), who, TenantMembershipId.random(), TenantRole.VIEWER);
    }
}
