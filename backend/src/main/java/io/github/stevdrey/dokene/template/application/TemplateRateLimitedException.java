package io.github.stevdrey.dokene.template.application;

import io.github.stevdrey.dokene.ratelimit.RetryAfter;
import java.time.Duration;
import java.util.Objects;

/**
 * The approval refresh budget is spent; carries only the delay for {@code Retry-After} and whether this refusal is the
 * one to audit (at most one per member per window).
 */
public class TemplateRateLimitedException extends TemplateRefusedException {
    private final Duration retryAfter;
    private final boolean audited;

    public TemplateRateLimitedException(Duration retryAfter, boolean audited) {
        super(TemplateErrorCode.RATE_LIMITED);
        this.retryAfter = Objects.requireNonNull(retryAfter, "Retry-after duration is required");
        this.audited = audited;
    }

    /** Whole seconds, rounded up and never below one. */
    public long retryAfterSeconds() {
        return RetryAfter.seconds(retryAfter);
    }

    public boolean audited() {
        return audited;
    }
}
