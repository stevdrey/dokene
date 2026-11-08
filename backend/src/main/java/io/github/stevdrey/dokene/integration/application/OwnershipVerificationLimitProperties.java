package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.ratelimit.CallLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budget of provider ownership proofs per tenant and per member within a sliding {@code window} (issue 321). Every
 * proof that reaches the provider counts, whatever its outcome, including unavailable answers; it complements the
 * rejection budget of {@link MappingRejectionLimitProperties}. Missing values take {@link #DEFAULTS}; non-positive
 * values fail startup.
 */
@ConfigurationProperties(prefix = "dokene.integration.whatsapp.ownership-verification-limit")
public record OwnershipVerificationLimitProperties(Integer tenantCalls, Integer actorCalls, Duration window) {
    public static final CallLimits DEFAULTS = new CallLimits(30, 20, Duration.ofMinutes(15));

    public OwnershipVerificationLimitProperties {
        CallLimits.resolve(tenantCalls, actorCalls, window, DEFAULTS);
    }

    public CallLimits limits() {
        return CallLimits.resolve(tenantCalls, actorCalls, window, DEFAULTS);
    }
}
