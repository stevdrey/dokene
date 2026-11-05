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
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CallBudgetTest {
    private static final Instant START = Instant.parse("2026-11-04T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(START);
    private final CallBudget<String> budget = new CallBudget<>(3, WINDOW, clock);

    @Test
    void grantsUpToTheLimitThenRefusesWithTheWaitUntilTheOldestCallLeaves() {
        assertThat(budget.acquire("a")).isEmpty();
        clock.advance(Duration.ofMinutes(1));
        assertThat(budget.acquire("a")).isEmpty();
        assertThat(budget.acquire("a")).isEmpty();

        clock.advance(Duration.ofMinutes(1));
        assertThat(budget.acquire("a")).contains(Duration.ofMinutes(13));
    }

    @Test
    void aRefusedCallRecordsNothingSoRetryingOnceTheWindowMovesSucceeds() {
        for (int call = 0; call < 3; call++) {
            assertThat(budget.acquire("a")).isEmpty();
        }
        for (int retry = 0; retry < 50; retry++) {
            assertThat(budget.acquire("a")).isPresent();
        }

        clock.advance(WINDOW);
        assertThat(budget.acquire("a")).isEmpty();
        assertThat(budget.acquire("a")).isEmpty();
        assertThat(budget.acquire("a")).isEmpty();
        assertThat(budget.acquire("a")).isPresent();
    }

    @Test
    void keysHaveIndependentBudgets() {
        for (int call = 0; call < 3; call++) {
            budget.acquire("a");
        }

        assertThat(budget.acquire("a")).isPresent();
        assertThat(budget.acquire("b")).isEmpty();
    }

    @Test
    void theWaitIsNeverBelowOneSecond() {
        for (int call = 0; call < 3; call++) {
            budget.acquire("a");
        }
        clock.advance(WINDOW.minusMillis(1));

        assertThat(budget.acquire("a")).contains(Duration.ofSeconds(1));
    }

    @Test
    void idleKeysAreEvictedOnceTheirCallsLeaveTheWindow() {
        budget.acquire("a");
        assertThat(budget.tracks("a")).isTrue();

        clock.advance(WINDOW.plusSeconds(1));
        budget.acquire("b");

        assertThat(budget.tracks("a")).isFalse();
        assertThat(budget.tracks("b")).isTrue();
    }

    @Test
    void concurrentCallersNeverExceedTheLimit() throws Exception {
        CallBudget<String> shared = new CallBudget<>(5, WINDOW, clock);
        AtomicInteger granted = new AtomicInteger();
        List<Callable<Void>> callers = new ArrayList<>();
        for (int caller = 0; caller < 64; caller++) {
            callers.add(() -> {
                if (shared.acquire("tenant").isEmpty()) {
                    granted.incrementAndGet();
                }
                return null;
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (Future<Void> result : pool.invokeAll(callers)) {
                result.get();
            }
        }

        assertThat(granted).hasValue(5);
    }

    @Test
    void rejectsInvalidConfigurationAndKeys() {
        assertThatThrownBy(() -> new CallBudget<String>(0, WINDOW, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallBudget<String>(1, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallBudget<String>(1, WINDOW.negated(), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.acquire(null)).isInstanceOf(NullPointerException.class);
        assertThat(Optional.<Duration>empty()).isEqualTo(new CallBudget<String>(1, WINDOW, clock).acquire("x"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

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
