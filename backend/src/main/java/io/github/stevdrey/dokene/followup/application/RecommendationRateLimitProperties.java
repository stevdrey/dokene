package io.github.stevdrey.dokene.followup.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AI recommendation rate limiting.
 */
@ConfigurationProperties(prefix = "dokene.ai.rate-limit")
public record RecommendationRateLimitProperties(
        Integer tenantPermitsPerMinute,
        Integer actorPermitsPerMinute,
        Duration retryAfter
) {
    public static final int DEFAULT_TENANT_PERMITS_PER_MINUTE = 60;
    public static final int DEFAULT_ACTOR_PERMITS_PER_MINUTE = 20;
    public static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(5);

    public RecommendationRateLimitProperties {
        if (tenantPermitsPerMinute == null || tenantPermitsPerMinute <= 0) {
            tenantPermitsPerMinute = DEFAULT_TENANT_PERMITS_PER_MINUTE;
        }
        if (actorPermitsPerMinute == null || actorPermitsPerMinute <= 0) {
            actorPermitsPerMinute = DEFAULT_ACTOR_PERMITS_PER_MINUTE;
        }
        if (retryAfter == null || retryAfter.isZero() || retryAfter.isNegative()) {
            retryAfter = DEFAULT_RETRY_AFTER;
        }
    }

    public static RecommendationRateLimitProperties defaults() {
        return new RecommendationRateLimitProperties(
                DEFAULT_TENANT_PERMITS_PER_MINUTE,
                DEFAULT_ACTOR_PERMITS_PER_MINUTE,
                DEFAULT_RETRY_AFTER);
    }
}
