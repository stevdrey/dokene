package io.github.stevdrey.dokene.ratelimit;

import java.time.Duration;
import java.util.Objects;

/** The {@code Retry-After} value of a rate-limit refusal. */
public final class RetryAfter {
    private RetryAfter() {
    }

    /** Whole seconds, rounded up and never below one, so a client never retries before the limit frees up. */
    public static long seconds(Duration delay) {
        Objects.requireNonNull(delay, "Delay is required");
        if (delay.isNegative() || delay.isZero()) {
            return 1;
        }
        long seconds = delay.getSeconds();
        return delay.getNano() > 0 ? seconds + 1 : Math.max(seconds, 1);
    }
}
