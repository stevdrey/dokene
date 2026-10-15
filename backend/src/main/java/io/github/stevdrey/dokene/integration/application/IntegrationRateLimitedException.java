package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.ratelimit.RetryAfter;
import java.time.Duration;
import java.util.Objects;

/** The mapping rejection budget is exhausted; carries only the delay for {@code Retry-After}. */
public class IntegrationRateLimitedException extends IntegrationRefusedException {
    private final Duration retryAfter;

    public IntegrationRateLimitedException(Duration retryAfter) {
        super(MessagingErrorCode.RATE_LIMITED);
        this.retryAfter = Objects.requireNonNull(retryAfter, "Retry-after duration is required");
    }

    /** Whole seconds, rounded up and never below one. */
    public long retryAfterSeconds() {
        return RetryAfter.seconds(retryAfter);
    }
}
