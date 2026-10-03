package io.github.stevdrey.dokene.ai.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounded retry policy for transient, side-effect-free AI generation requests. The total deadline of an
 * invocation is always the requested timeout; retries never extend it. {@code maxAttempts=1} disables retry.
 */
@ConfigurationProperties(prefix = "dokene.ai.retry")
public record AiRetryProperties(Integer maxAttempts, Duration initialBackoff, Duration maxBackoff,
        Duration minAttemptBudget) {
    public static final int DEFAULT_MAX_ATTEMPTS = 2;
    public static final int HARD_MAX_ATTEMPTS = 3;
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(250);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(2);
    public static final Duration DEFAULT_MIN_ATTEMPT_BUDGET = Duration.ofSeconds(1);
    /** Ceiling that also keeps the exponential backoff shift far from long overflow. */
    public static final Duration HARD_MAX_BACKOFF = Duration.ofSeconds(30);

    public AiRetryProperties {
        if (maxAttempts == null) {
            maxAttempts = DEFAULT_MAX_ATTEMPTS;
        }
        if (maxAttempts < 1 || maxAttempts > HARD_MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "dokene.ai.retry.max-attempts must be between 1 and " + HARD_MAX_ATTEMPTS);
        }
        initialBackoff = positiveOrDefault(initialBackoff, DEFAULT_INITIAL_BACKOFF, "initial-backoff");
        maxBackoff = positiveOrDefault(maxBackoff, DEFAULT_MAX_BACKOFF, "max-backoff");
        minAttemptBudget = positiveOrDefault(minAttemptBudget, DEFAULT_MIN_ATTEMPT_BUDGET, "min-attempt-budget");
        if (initialBackoff.compareTo(HARD_MAX_BACKOFF) > 0 || maxBackoff.compareTo(HARD_MAX_BACKOFF) > 0) {
            throw new IllegalArgumentException(
                    "dokene.ai.retry backoff values must not exceed " + HARD_MAX_BACKOFF.toSeconds() + "s");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("dokene.ai.retry.max-backoff must be >= initial-backoff");
        }
    }

    public static AiRetryProperties defaults() {
        return new AiRetryProperties(null, null, null, null);
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("dokene.ai.retry." + name + " must be positive");
        }
        return value;
    }
}
