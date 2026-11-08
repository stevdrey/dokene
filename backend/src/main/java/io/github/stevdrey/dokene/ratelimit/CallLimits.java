package io.github.stevdrey.dokene.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * Validated limits of an {@link ActorCallBudget}: calls per tenant and per actor within one sliding {@code window}.
 * The single place where these limits are defaulted and checked; non-positive values are refused.
 */
public record CallLimits(int tenantCalls, int actorCalls, Duration window) {
    public CallLimits {
        Objects.requireNonNull(window, "Window is required");
        if (tenantCalls <= 0 || actorCalls <= 0) {
            throw new IllegalArgumentException("Call limits must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Call window must be positive");
        }
    }

    /** Bound configuration values: each missing value takes the one of {@code defaults}. */
    public static CallLimits resolve(Integer tenantCalls, Integer actorCalls, Duration window, CallLimits defaults) {
        Objects.requireNonNull(defaults, "Defaults are required");
        return new CallLimits(tenantCalls == null ? defaults.tenantCalls() : tenantCalls,
                actorCalls == null ? defaults.actorCalls() : actorCalls,
                window == null ? defaults.window() : window);
    }
}
