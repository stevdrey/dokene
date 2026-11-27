// ABOUTME: Capped exponential backoff with jitter for follow-up work retries (issue 461, ADR 0038). Pure arithmetic
// ABOUTME: over an injected random source, so tests and instances agree on its bounds.
package io.github.stevdrey.dokene.followup.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The delay after attempt {@code n} is {@code min(maxBackoff, initialBackoff * 2^(n-1))}, less a random share of at most
 * {@code jitter} of it, so instances that failed together do not retry together. A delay is never negative and never
 * exceeds {@code maxBackoff}.
 */
@Component
@EnableConfigurationProperties(FollowUpRetryProperties.class)
public class FollowUpRetryPolicy {
    private final FollowUpRetryProperties properties;
    private final DoubleSupplier random;

    @Autowired
    public FollowUpRetryPolicy(FollowUpRetryProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** @param random values in {@code [0, 1)} */
    FollowUpRetryPolicy(FollowUpRetryProperties properties, DoubleSupplier random) {
        this.properties = Objects.requireNonNull(properties);
        this.random = Objects.requireNonNull(random);
    }

    public FollowUpRetryProperties properties() {
        return properties;
    }

    /** The delay before retrying after the given attempt number, starting at 1. */
    public Duration backoff(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("Attempt must be positive");
        }
        long initial = properties.initialBackoff().toMillis();
        long max = properties.maxBackoff().toMillis();
        // 2^(n-1) overflows long past 62 doublings; the cap is reached far earlier for any accepted configuration.
        int doublings = Math.min(attempt - 1, 40);
        long capped = initial > (max >> doublings) ? max : Math.min(max, initial << doublings);
        double share = Math.clamp(random.getAsDouble(), 0.0, 1.0) * properties.jitter();
        return Duration.ofMillis(capped - (long) (capped * share));
    }

    /** When an item that failed on {@code attempt} at {@code now} may be claimed again. */
    public Instant retryAt(Instant now, int attempt) {
        return Objects.requireNonNull(now, "Instant is required").plus(backoff(attempt));
    }
}
