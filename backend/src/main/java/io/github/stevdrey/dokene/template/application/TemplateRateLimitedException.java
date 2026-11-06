package io.github.stevdrey.dokene.template.application;

import io.github.stevdrey.dokene.ratelimit.RetryAfter;
import java.time.Duration;
import java.util.Objects;

/** The tenant's approval refresh budget is spent; carries only the delay for {@code Retry-After}. */
public class TemplateRateLimitedException extends TemplateRefusedException {
    private final Duration retryAfter;

    public TemplateRateLimitedException(Duration retryAfter) {
        super(TemplateErrorCode.RATE_LIMITED);
        this.retryAfter = Objects.requireNonNull(retryAfter, "Retry-after duration is required");
    }

    /** Whole seconds, rounded up and never below one. */
    public long retryAfterSeconds() {
        return RetryAfter.seconds(retryAfter);
    }
}
