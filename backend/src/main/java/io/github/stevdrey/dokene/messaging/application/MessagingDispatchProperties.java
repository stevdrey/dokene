// ABOUTME: Send policy bounds for manual dispatch (issue 138, ADR 0023 §7, ADR 0024 §5): attempt cap, minimum
// ABOUTME: interval between messages to one customer, per-tenant rate of started attempts and provider call deadline.
package io.github.stevdrey.dokene.messaging.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code maxSendAttempts}: provider attempts per message before a transient failure becomes FAILED.
 * {@code minContactInterval}: no send while another message reached this customer within the interval.
 * {@code sendRatePerTenantPerMinute}: attempts any tenant may start in a sliding minute.
 * {@code sendTimeout}: the dispatch deadline on one provider call; past it the outcome is unknown.
 */
@ConfigurationProperties(prefix = "dokene.messaging")
public record MessagingDispatchProperties(Integer maxSendAttempts, Duration minContactInterval,
        Integer sendRatePerTenantPerMinute, Duration sendTimeout) {
    public static final int DEFAULT_MAX_SEND_ATTEMPTS = 3;
    public static final Duration DEFAULT_MIN_CONTACT_INTERVAL = Duration.ofHours(24);
    public static final int DEFAULT_SEND_RATE_PER_TENANT_PER_MINUTE = 30;
    public static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);

    public MessagingDispatchProperties {
        maxSendAttempts = maxSendAttempts == null ? DEFAULT_MAX_SEND_ATTEMPTS : maxSendAttempts;
        minContactInterval = minContactInterval == null ? DEFAULT_MIN_CONTACT_INTERVAL : minContactInterval;
        sendRatePerTenantPerMinute = sendRatePerTenantPerMinute == null ? DEFAULT_SEND_RATE_PER_TENANT_PER_MINUTE
                : sendRatePerTenantPerMinute;
        sendTimeout = sendTimeout == null ? DEFAULT_SEND_TIMEOUT : sendTimeout;
        if (maxSendAttempts < 1) {
            throw new IllegalArgumentException("Maximum send attempts must be at least 1");
        }
        if (minContactInterval.isNegative()) {
            throw new IllegalArgumentException("Minimum contact interval cannot be negative");
        }
        if (sendRatePerTenantPerMinute < 1) {
            throw new IllegalArgumentException("Send rate must be at least 1 per minute");
        }
        if (sendTimeout.isZero() || sendTimeout.isNegative()) {
            throw new IllegalArgumentException("Send timeout must be positive");
        }
    }
}
