package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.ratelimit.RetryAfter;
import java.time.Duration;
import java.util.Objects;

/**
 * The mapping rejection or provider proof budget is exhausted; carries only the delay for {@code Retry-After} and
 * whether this refusal is to be audited. Rejection-budget refusals are always audited; proof-budget refusals at most
 * once per member per window.
 */
public class IntegrationRateLimitedException extends IntegrationRefusedException {
    private final Duration retryAfter;
    private final boolean audited;

    public IntegrationRateLimitedException(Duration retryAfter) {
        this(retryAfter, true);
    }

    public IntegrationRateLimitedException(Duration retryAfter, boolean audited) {
        super(MessagingErrorCode.RATE_LIMITED);
        this.retryAfter = Objects.requireNonNull(retryAfter, "Retry-after duration is required");
        this.audited = audited;
    }

    public boolean audited() {
        return audited;
    }

    /** Whole seconds, rounded up and never below one. */
    public long retryAfterSeconds() {
        return RetryAfter.seconds(retryAfter);
    }
}
