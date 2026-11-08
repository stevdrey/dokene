package io.github.stevdrey.dokene.template.application;

import io.github.stevdrey.dokene.ratelimit.CallLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budget of provider requests made by approval refreshes, per tenant and per member within a sliding {@code window}
 * (issue 321). Missing values take {@link #DEFAULTS}; non-positive values fail startup.
 */
@ConfigurationProperties(prefix = "dokene.template.refresh-limit")
public record TemplateRefreshLimitProperties(Integer tenantCalls, Integer actorCalls, Duration window) {
    public static final CallLimits DEFAULTS = new CallLimits(30, 20, Duration.ofMinutes(15));

    public TemplateRefreshLimitProperties {
        CallLimits.resolve(tenantCalls, actorCalls, window, DEFAULTS);
    }

    public CallLimits limits() {
        return CallLimits.resolve(tenantCalls, actorCalls, window, DEFAULTS);
    }
}
