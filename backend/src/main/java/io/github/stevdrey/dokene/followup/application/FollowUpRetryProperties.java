// ABOUTME: Bounds for follow-up work retries and recovery (issue 461, ADR 0038): capped exponential backoff with
// ABOUTME: jitter, and how many times an operator may requeue one exhausted item.
package io.github.stevdrey.dokene.followup.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Attempts per item stay bounded by {@code dokene.follow-up.work.max-attempts} (ADR 0037).
 *
 * @param initialBackoff the delay before the first retry
 * @param maxBackoff the longest delay before any retry
 * @param jitter the fraction of each delay that is randomized away, from 0 (none) to 1
 * @param maxRequeues how many times recovery may requeue one item; 0 disables requeueing
 */
@ConfigurationProperties(prefix = "dokene.follow-up.retry")
public record FollowUpRetryProperties(Duration initialBackoff, Duration maxBackoff, Double jitter,
        Integer maxRequeues) {
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMinutes(1);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofMinutes(30);
    public static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    public static final Duration MAX_BACKOFF = Duration.ofHours(24);
    public static final double DEFAULT_JITTER = 0.2;
    public static final int DEFAULT_MAX_REQUEUES = 3;
    public static final int MAX_MAX_REQUEUES = 20;

    public FollowUpRetryProperties {
        initialBackoff = initialBackoff == null ? DEFAULT_INITIAL_BACKOFF : initialBackoff;
        maxBackoff = maxBackoff == null ? DEFAULT_MAX_BACKOFF : maxBackoff;
        jitter = jitter == null ? DEFAULT_JITTER : jitter;
        maxRequeues = maxRequeues == null ? DEFAULT_MAX_REQUEUES : maxRequeues;
        if (initialBackoff.compareTo(MIN_BACKOFF) < 0 || initialBackoff.compareTo(MAX_BACKOFF) > 0) {
            throw new IllegalArgumentException("Retry initial backoff must be between 1 second and 24 hours");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0 || maxBackoff.compareTo(MAX_BACKOFF) > 0) {
            throw new IllegalArgumentException("Retry max backoff must be between the initial backoff and 24 hours");
        }
        if (!(jitter >= 0.0 && jitter <= 1.0)) {
            throw new IllegalArgumentException("Retry jitter must be between 0 and 1");
        }
        if (maxRequeues < 0 || maxRequeues > MAX_MAX_REQUEUES) {
            throw new IllegalArgumentException("Retry max requeues must be between 0 and " + MAX_MAX_REQUEUES);
        }
    }
}
