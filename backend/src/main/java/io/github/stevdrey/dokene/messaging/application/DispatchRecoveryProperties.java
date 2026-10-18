// ABOUTME: Bounds for the dispatch recovery processor (issue 138, ADR 0028 §5): whether it runs, how old a STARTED
// ABOUTME: claim must be before it counts as abandoned, how many claims one run handles and how long runs are apart.
package io.github.stevdrey.dokene.messaging.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code claimTimeout} must exceed the dispatch deadline ({@code dokene.messaging.send-timeout}) by a wide margin: a
 * claim younger than that may still belong to a call in flight, and recording it as unknown would race the inline
 * outcome.
 */
@ConfigurationProperties(prefix = "dokene.messaging.recovery")
public record DispatchRecoveryProperties(Boolean enabled, Duration claimTimeout, Integer batchSize,
        Duration interval) {
    public static final Duration DEFAULT_CLAIM_TIMEOUT = Duration.ofMinutes(10);
    public static final int DEFAULT_BATCH_SIZE = 50;
    public static final int MAX_BATCH_SIZE = 500;
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);

    public DispatchRecoveryProperties {
        enabled = enabled != null && enabled;
        claimTimeout = claimTimeout == null ? DEFAULT_CLAIM_TIMEOUT : claimTimeout;
        batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        interval = interval == null ? DEFAULT_INTERVAL : interval;
        if (claimTimeout.isNegative()) {
            throw new IllegalArgumentException("Claim timeout cannot be negative");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Recovery batch size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("Recovery interval must be positive");
        }
    }
}
