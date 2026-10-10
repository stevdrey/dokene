// ABOUTME: Typed binding of the dokene.messaging send settings: global switch, attempt cap, timeout, rate limit.
// ABOUTME: The global switch defaults to off, so nothing leaves a fresh deployment until it is configured.
package io.github.stevdrey.dokene.messaging.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dokene.messaging")
public record OutboundMessagingProperties(Outbound outbound, int maxSendAttempts, Duration sendTimeout,
        SendRateLimit sendRateLimit) {
    public static final int DEFAULT_MAX_SEND_ATTEMPTS = 3;
    public static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_PER_TENANT_PER_MINUTE = 30;

    public OutboundMessagingProperties {
        outbound = outbound == null ? new Outbound(false) : outbound;
        maxSendAttempts = maxSendAttempts <= 0 ? DEFAULT_MAX_SEND_ATTEMPTS : maxSendAttempts;
        sendTimeout = sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()
                ? DEFAULT_SEND_TIMEOUT : sendTimeout;
        sendRateLimit = sendRateLimit == null ? new SendRateLimit(DEFAULT_PER_TENANT_PER_MINUTE) : sendRateLimit;
    }

    public record Outbound(boolean enabled) {
    }

    public record SendRateLimit(int perTenantPerMinute) {
        public SendRateLimit {
            perTenantPerMinute = perTenantPerMinute <= 0 ? DEFAULT_PER_TENANT_PER_MINUTE : perTenantPerMinute;
        }
    }
}
