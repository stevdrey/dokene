package io.github.stevdrey.dokene.integration.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budget of provider ownership proofs per tenant within a sliding {@code window} (issue 321). Every proof that reaches
 * the provider counts, whatever its outcome, including unavailable answers; it complements the rejection budget of
 * {@link MappingRejectionLimitProperties}. Missing values take the defaults; non-positive values fail startup.
 */
@ConfigurationProperties(prefix = "dokene.integration.whatsapp.ownership-verification-limit")
public record OwnershipVerificationLimitProperties(Integer tenantCalls, Duration window) {
    public static final int DEFAULT_TENANT_CALLS = 30;
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);

    public OwnershipVerificationLimitProperties {
        tenantCalls = tenantCalls == null ? DEFAULT_TENANT_CALLS : tenantCalls;
        window = window == null ? DEFAULT_WINDOW : window;
        if (tenantCalls <= 0) {
            throw new IllegalArgumentException("Ownership verification limit must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Ownership verification window must be positive");
        }
    }

    public static OwnershipVerificationLimitProperties defaults() {
        return new OwnershipVerificationLimitProperties(null, null);
    }
}
