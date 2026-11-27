// ABOUTME: Bounds for the durable follow-up work ledger (issue 458, ADR 0037): batch size, lease duration and attempts
// ABOUTME: per item. Scheduling and enablement belong to the scheduler wiring (issue 463); backoff belongs to issue 461.
package io.github.stevdrey.dokene.followup.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param batchSize the most items one enqueue or claim call may touch
 * @param leaseDuration how long a claim, or a renewal, holds an item; it must exceed the clock skew between instances
 * @param maxAttempts claims an item may receive before it is exhausted
 */
@ConfigurationProperties(prefix = "dokene.follow-up.work")
public record FollowUpWorkProperties(Integer batchSize, Duration leaseDuration, Integer maxAttempts) {
    public static final int DEFAULT_BATCH_SIZE = 20;
    public static final int MAX_BATCH_SIZE = 200;
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(2);
    public static final Duration MIN_LEASE_DURATION = Duration.ofSeconds(10);
    public static final Duration MAX_LEASE_DURATION = Duration.ofHours(1);
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final int MAX_MAX_ATTEMPTS = 20;

    public FollowUpWorkProperties {
        batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        leaseDuration = leaseDuration == null ? DEFAULT_LEASE_DURATION : leaseDuration;
        maxAttempts = maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Work batch size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (leaseDuration.compareTo(MIN_LEASE_DURATION) < 0 || leaseDuration.compareTo(MAX_LEASE_DURATION) > 0) {
            throw new IllegalArgumentException("Work lease duration must be between 10 seconds and 1 hour");
        }
        if (maxAttempts < 1 || maxAttempts > MAX_MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Work max attempts must be between 1 and " + MAX_MAX_ATTEMPTS);
        }
    }
}
