// ABOUTME: Pins the follow-up retry backoff (issue 461, ADR 0038): exponential growth from the initial delay, the cap,
// ABOUTME: the jitter bounds, and the configuration limits.
package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FollowUpRetryPolicyTest {
    private static final FollowUpRetryProperties PROPERTIES = new FollowUpRetryProperties(Duration.ofMinutes(1),
            Duration.ofMinutes(10), 0.25, 3);

    @Test
    void doublesFromTheInitialDelayUntilTheCapWithoutJitter() {
        FollowUpRetryPolicy policy = new FollowUpRetryPolicy(PROPERTIES, () -> 0.0);

        assertThat(policy.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.backoff(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(policy.backoff(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(policy.backoff(5)).isEqualTo(Duration.ofMinutes(10));
        // Far past the cap the doubling never overflows.
        assertThat(policy.backoff(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void jitterRemovesAtMostItsShareOfTheDelay() {
        FollowUpRetryPolicy most = new FollowUpRetryPolicy(PROPERTIES, () -> 0.999_999);
        FollowUpRetryPolicy half = new FollowUpRetryPolicy(PROPERTIES, () -> 0.5);

        assertThat(most.backoff(1)).isGreaterThan(Duration.ofSeconds(45)).isLessThanOrEqualTo(Duration.ofMinutes(1));
        assertThat(half.backoff(2)).isEqualTo(Duration.ofSeconds(105));
        assertThat(most.backoff(9)).isGreaterThan(Duration.ofMinutes(7).plusSeconds(30));
    }

    @Test
    void theDefaultRandomSourceStaysWithinTheBounds() {
        FollowUpRetryPolicy policy = new FollowUpRetryPolicy(PROPERTIES);
        for (int attempt = 1; attempt <= 8; attempt++) {
            Duration delay = policy.backoff(attempt);
            Duration ceiling = Duration.ofMinutes(Math.min(10, 1L << (attempt - 1)));
            assertThat(delay).isLessThanOrEqualTo(ceiling)
                    .isGreaterThanOrEqualTo(Duration.ofMillis((long) (ceiling.toMillis() * 0.75)));
        }
    }

    @Test
    void retriesAfterTheBackoffFromTheGivenInstant() {
        Instant now = Instant.parse("2026-11-27T12:00:00Z");
        assertThat(new FollowUpRetryPolicy(PROPERTIES, () -> 0.0).retryAt(now, 2)).isEqualTo(now.plusSeconds(120));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsAttemptsBeforeTheFirst(int attempt) {
        assertThatThrownBy(() -> new FollowUpRetryPolicy(PROPERTIES).backoff(attempt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsAndConfigurationBounds() {
        FollowUpRetryProperties defaults = new FollowUpRetryProperties(null, null, null, null);
        assertThat(defaults.initialBackoff()).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.maxBackoff()).isEqualTo(Duration.ofMinutes(30));
        assertThat(defaults.jitter()).isEqualTo(0.2);
        assertThat(defaults.maxRequeues()).isEqualTo(3);

        assertThatThrownBy(() -> new FollowUpRetryProperties(Duration.ofMillis(999), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpRetryProperties(Duration.ofMinutes(5), Duration.ofMinutes(4), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpRetryProperties(null, Duration.ofHours(25), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpRetryProperties(null, null, 1.5, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpRetryProperties(null, null, Double.NaN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpRetryProperties(null, null, null, 21))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FollowUpRetryProperties(null, null, 0.0, 0).maxRequeues()).isZero();
    }
}
