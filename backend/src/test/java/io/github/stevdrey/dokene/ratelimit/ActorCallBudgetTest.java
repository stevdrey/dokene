package io.github.stevdrey.dokene.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActorCallBudgetTest {
    private static final Instant START = Instant.parse("2026-11-07T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(START);
    private final ActorCallBudget<String, String> budget = new ActorCallBudget<>(new CallLimits(5, 3, WINDOW), clock);

    /** The refusal as the test sees it: the budget hands it to the factory, which the test turns into an exception. */
    private static final class Refused extends RuntimeException {
        final ActorCallBudget.Refusal refusal;

        Refused(ActorCallBudget.Refusal refusal) {
            this.refusal = refusal;
        }
    }

    private ActorCallBudget.Ticket admit(String tenant, String actor) {
        return budget.admit(tenant, actor, Refused::new);
    }

    private ActorCallBudget.Refusal refusal(String tenant, String actor) {
        try {
            admit(tenant, actor);
        } catch (Refused refused) {
            return refused.refusal;
        }
        throw new AssertionError("Expected a refusal");
    }

    @Test
    void anActorIsHeldToItsShareAndTheTenantLimitSpansActors() {
        for (int call = 0; call < 3; call++) {
            admit("t", "a");
        }
        assertThat(refusal("t", "a").retryAfter()).isEqualTo(WINDOW);

        admit("t", "b");
        admit("t", "b");
        // The tenant has spent 5: a third actor with its own share untouched is still refused.
        assertThat(refusal("t", "c").retryAfter()).isEqualTo(WINDOW);
        // Another tenant is independent.
        admit("u", "a");
    }

    @Test
    void theWaitIsTheLongerOfBothLevelsAndNeverBelowOneSecond() {
        admit("t", "a");
        clock.advance(Duration.ofMinutes(5));
        admit("t", "a");
        admit("t", "a");
        clock.advance(Duration.ofMinutes(1));
        assertThat(refusal("t", "a").retryAfter()).isEqualTo(Duration.ofMinutes(9));

        clock.advance(Duration.ofMinutes(9).minusMillis(1));
        assertThat(refusal("t", "a").retryAfter()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void refusalsRecordNothingSoARetryAfterTheWindowSucceeds() {
        for (int call = 0; call < 3; call++) {
            admit("t", "a");
        }
        for (int retry = 0; retry < 50; retry++) {
            refusal("t", "a");
        }

        clock.advance(WINDOW);
        for (int call = 0; call < 3; call++) {
            admit("t", "a");
        }
        refusal("t", "a");
    }

    @Test
    void settlingWithNoRequestRefundsTheAdmissionAtBothLevels() {
        for (int call = 0; call < 3; call++) {
            admit("t", "a").settle(0);
        }
        for (int call = 0; call < 3; call++) {
            admit("t", "a");
        }
        refusal("t", "a");
    }

    @Test
    void settlingWithSeveralRequestsChargesTheRestAndSettlesOnlyOnce() {
        ActorCallBudget.Ticket ticket = admit("t", "a");
        ticket.settle(3);
        ticket.settle(5);
        ticket.settle(0);

        refusal("t", "a");
        admit("t", "b");
        admit("t", "b");
        refusal("t", "b");
        assertThatThrownBy(() -> admit("u", "c").settle(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAdmittedOperationMayOvershootTheLimitByItsOwnExtraRequestsOnly() {
        admit("t", "a").settle(2);
        admit("t", "a").settle(5);

        refusal("t", "a");
        refusal("t", "b");
    }

    @Test
    void aReservationNeedsEveryUnitAndRefundsWhatTheOperationDidNotUse() {
        ActorCallBudget<String, String> wide = new ActorCallBudget<>(new CallLimits(12, 7, WINDOW), clock);
        wide.admit("t", "a", 5, Refused::new).settle(1);
        wide.admit("t", "a", 5, Refused::new).settle(2);
        // Three units are kept, four are left: a full reservation of five no longer fits, a single unit still does.
        assertThatThrownBy(() -> wide.admit("t", "a", 5, Refused::new)).isInstanceOf(Refused.class);
        wide.admit("t", "a", Refused::new);

        assertThatThrownBy(() -> wide.admit("t", "b", 0, Refused::new)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> wide.admit("t", "b", 8, Refused::new)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overlappingReservationsNeverExceedEitherLimit() {
        ActorCallBudget<String, String> wide = new ActorCallBudget<>(new CallLimits(30, 20, WINDOW), clock);
        List<ActorCallBudget.Ticket> inFlight = new ArrayList<>();
        // Four unsettled five-request operations hold the member's whole share before any page is settled.
        for (int operation = 0; operation < 4; operation++) {
            inFlight.add(wide.admit("t", "a", 5, Refused::new));
        }
        assertThatThrownBy(() -> wide.admit("t", "a", 5, Refused::new)).isInstanceOf(Refused.class);
        inFlight.add(wide.admit("t", "b", 5, Refused::new));
        inFlight.add(wide.admit("t", "b", 5, Refused::new));
        assertThatThrownBy(() -> wide.admit("t", "c", 5, Refused::new)).isInstanceOf(Refused.class);

        // Settled at five requests each, the tenant has spent exactly its limit and nobody was locked out early.
        inFlight.forEach(ticket -> ticket.settle(5));
        assertThatThrownBy(() -> wide.admit("t", "c", Refused::new)).isInstanceOf(Refused.class);
    }

    @Test
    void concurrentReservationsNeverExceedEitherLimit() throws Exception {
        ActorCallBudget<String, String> shared = new ActorCallBudget<>(new CallLimits(30, 20, WINDOW), clock);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger grantedToA = new AtomicInteger();
        List<Callable<Void>> callers = new ArrayList<>();
        for (int caller = 0; caller < 96; caller++) {
            String actor = "actor" + (caller % 3);
            callers.add(() -> {
                try {
                    shared.admit("tenant", actor, 5, Refused::new).settle(5);
                    granted.incrementAndGet();
                    if (actor.equals("actor0")) {
                        grantedToA.incrementAndGet();
                    }
                } catch (Refused ignored) {
                    // expected once a limit is reached
                }
                return null;
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (Future<Void> result : pool.invokeAll(callers)) {
                result.get();
            }
        }

        assertThat(granted).hasValue(6);
        assertThat(grantedToA.get()).isLessThanOrEqualTo(4);
    }

    @Test
    void onlyTheFirstRefusalOfEachActorPerWindowIsReported() {
        for (int call = 0; call < 3; call++) {
            admit("t", "a");
        }
        assertThat(refusal("t", "a").report()).isTrue();
        assertThat(refusal("t", "a").report()).isFalse();
        assertThat(refusal("t", "a").report()).isFalse();

        admit("t", "b");
        admit("t", "b");
        assertThat(refusal("t", "b").report()).isTrue();
        assertThat(refusal("t", "b").report()).isFalse();

        clock.advance(WINDOW.minusSeconds(1));
        assertThat(refusal("t", "a").report()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        for (int call = 0; call < 3; call++) {
            admit("t", "a");
        }
        assertThat(refusal("t", "a").report()).isTrue();
    }

    @Test
    void idleKeysAndOldReportsAreEvicted() {
        for (int call = 0; call < 3; call++) {
            admit("t", "a");
        }
        refusal("t", "a");
        assertThat(budget.tracks("t", "a")).isTrue();

        clock.advance(WINDOW.plusSeconds(1));
        admit("u", "b");

        assertThat(budget.tracks("t", "a")).isFalse();
        assertThat(budget.tracks("u", "b")).isTrue();
    }

    @Test
    void concurrentCallersNeverExceedEitherLimit() throws Exception {
        ActorCallBudget<String, String> shared = new ActorCallBudget<>(new CallLimits(10, 4, WINDOW), clock);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger grantedToA = new AtomicInteger();
        List<Callable<Void>> callers = new ArrayList<>();
        for (int caller = 0; caller < 96; caller++) {
            String actor = "actor" + (caller % 6);
            callers.add(() -> {
                try {
                    shared.admit("tenant", actor, Refused::new);
                    granted.incrementAndGet();
                    if (actor.equals("actor0")) {
                        grantedToA.incrementAndGet();
                    }
                } catch (Refused ignored) {
                    // expected once a limit is reached
                }
                return null;
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (Future<Void> result : pool.invokeAll(callers)) {
                result.get();
            }
        }

        assertThat(granted).hasValue(10);
        assertThat(grantedToA.get()).isLessThanOrEqualTo(4);
    }

    @Test
    void rejectsMissingKeysAndFactories() {
        assertThatThrownBy(() -> budget.admit(null, "a", Refused::new)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> budget.admit("t", null, Refused::new)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> budget.admit("t", "a", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ActorCallBudget<String, String>(null, clock))
                .isInstanceOf(NullPointerException.class);
    }

    /** A test clock that only moves when told to. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
