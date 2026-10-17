package io.github.stevdrey.dokene.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SlidingWindowTest {
    private static final Instant START = Instant.parse("2026-10-14T10:00:00Z");
    private final SlidingWindow window = new SlidingWindow(Duration.ofMinutes(1));

    @Test
    void anEventLeavesTheWindowOnceItIsExactlyOneWindowOld() {
        window.record(START);

        assertThat(window.count(START.plusSeconds(59))).isEqualTo(1);
        assertThat(window.count(START.plusSeconds(60))).isZero();
        assertThat(window.isEmpty()).isTrue();
    }

    @Test
    void untilBelowWaitsForEnoughEventsToExpire() {
        window.record(START);
        window.record(START.plusSeconds(10));
        window.record(START.plusSeconds(20));
        Instant now = START.plusSeconds(30);

        assertThat(window.untilBelow(4, now)).isZero();
        assertThat(window.untilBelow(3, now)).isEqualTo(Duration.ofSeconds(30));
        assertThat(window.untilBelow(2, now)).isEqualTo(Duration.ofSeconds(40));
        assertThat(window.untilBelow(1, START.plusMillis(59_500))).isEqualTo(Duration.ofSeconds(20).plusMillis(500));
    }

    @Test
    void untilBelowNeverAsksForLessThanOneSecond() {
        window.record(START);

        assertThat(window.untilBelow(1, START.plusMillis(59_900))).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void removeLatestUndoesTheMostRecentMatchingEvent() {
        window.record(START);
        window.record(START.plusSeconds(1));

        assertThat(window.removeLatest(START.plusSeconds(1))).isTrue();
        assertThat(window.removeLatest(START.plusSeconds(1))).isFalse();
        assertThat(window.count(START.plusSeconds(2))).isEqualTo(1);
    }

    @Test
    void refusesNonPositiveLengthsAndLimits() {
        assertThatThrownBy(() -> new SlidingWindow(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> window.untilBelow(0, START)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryAfterRoundsUpToWholeSecondsAndIsNeverBelowOne() {
        assertThat(RetryAfter.seconds(Duration.ofMillis(1500))).isEqualTo(2);
        assertThat(RetryAfter.seconds(Duration.ofSeconds(42))).isEqualTo(42);
        assertThat(RetryAfter.seconds(Duration.ofMillis(10))).isEqualTo(1);
        assertThat(RetryAfter.seconds(Duration.ZERO)).isEqualTo(1);
        assertThat(RetryAfter.seconds(Duration.ofSeconds(-5))).isEqualTo(1);
    }
}
