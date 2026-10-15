package io.github.stevdrey.dokene.integration.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budget of rejected phone-number-id mappings (a number already mapped by another tenant) per tenant and per actor
 * within a sliding {@code window}. Missing values take the defaults; non-positive values fail startup.
 */
@ConfigurationProperties(prefix = "dokene.integration.whatsapp.mapping-rejection-limit")
public record MappingRejectionLimitProperties(Integer tenantRejections, Integer actorRejections, Duration window) {
    public static final int DEFAULT_TENANT_REJECTIONS = 10;
    public static final int DEFAULT_ACTOR_REJECTIONS = 5;
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);

    public MappingRejectionLimitProperties {
        tenantRejections = tenantRejections == null ? DEFAULT_TENANT_REJECTIONS : tenantRejections;
        actorRejections = actorRejections == null ? DEFAULT_ACTOR_REJECTIONS : actorRejections;
        window = window == null ? DEFAULT_WINDOW : window;
        if (tenantRejections <= 0 || actorRejections <= 0) {
            throw new IllegalArgumentException("Mapping rejection limits must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Mapping rejection window must be positive");
        }
    }

    public static MappingRejectionLimitProperties defaults() {
        return new MappingRejectionLimitProperties(null, null, null);
    }
}
