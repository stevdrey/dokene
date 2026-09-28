package io.github.stevdrey.dokene.followup.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Thrown when recommendation requests exceed configured per-tenant or per-actor rate limits.
 */
public class RecommendationRateLimitExceededException extends RuntimeException {
    private final Duration retryAfter;

    public RecommendationRateLimitExceededException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = Objects.requireNonNull(retryAfter, "Retry-after duration is required");
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public long retryAfterSeconds() {
        long seconds = retryAfter.toSeconds();
        return seconds > 0 ? seconds : 1;
    }
}
