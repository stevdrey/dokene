// ABOUTME: The tenant send rate refusal (RATE_LIMITED, 429): also carries how long until the sliding window frees a
// ABOUTME: slot, so the route can answer with Retry-After (ADR 0023 §4.4). Nothing else about other sends leaks.
package io.github.stevdrey.dokene.messaging.domain;

import java.time.Duration;
import java.util.Objects;

public class MessagingRateLimitedException extends MessagingRefusedException {
    private final Duration retryAfter;

    public MessagingRateLimitedException(Duration retryAfter) {
        super(MessagingErrorCode.RATE_LIMITED);
        this.retryAfter = Objects.requireNonNull(retryAfter, "Retry delay is required");
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
