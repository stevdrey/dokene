// ABOUTME: Webhook inbox settings under dokene.integration.whatsapp.webhook.inbox (issue 139, ADR 0030).
// ABOUTME: Validated at startup; park and abandon windows bound retries, retention bounds dedup rows.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The sweeper interval ({@code interval}) is read by {@code @Scheduled} directly and is not repeated here. The abandon
 * window must outlast an ordinary audit or database outage, so acknowledged work is only given up after a sustained
 * incident.
 */
@ConfigurationProperties("dokene.integration.whatsapp.webhook.inbox")
public record WebhookInboxProperties(Boolean enabled, Duration parkWindow, Duration abandonAfter, Duration retention,
        Integer batchSize, Integer inlineLimit) {
    static final int MAX_BATCH_SIZE = 500;
    /** Meta batches at most 1000 statuses, so a larger inline limit could never apply. */
    static final int MAX_INLINE_LIMIT = 1000;
    static final Duration MIN_ABANDON_AFTER = Duration.ofHours(1);
    /** Meta retries an unacknowledged callback for up to 7 days; dedup rows must outlive every retry. */
    static final Duration MIN_RETENTION = Duration.ofDays(8);

    public WebhookInboxProperties {
        enabled = enabled == null || enabled;
        parkWindow = parkWindow == null ? Duration.ofMinutes(15) : parkWindow;
        abandonAfter = abandonAfter == null ? Duration.ofDays(1) : abandonAfter;
        retention = retention == null ? Duration.ofDays(30) : retention;
        batchSize = batchSize == null ? 100 : batchSize;
        inlineLimit = inlineLimit == null ? 50 : inlineLimit;
        if (parkWindow.isNegative() || parkWindow.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException(
                    "dokene.integration.whatsapp.webhook.inbox.park-window must be between 0 and 1 hour");
        }
        if (abandonAfter.compareTo(MIN_ABANDON_AFTER) < 0 || abandonAfter.compareTo(parkWindow) < 0) {
            throw new IllegalStateException("dokene.integration.whatsapp.webhook.inbox.abandon-after must be at least "
                    + MIN_ABANDON_AFTER + " and at least the park window");
        }
        if (retention.compareTo(MIN_RETENTION) < 0 || retention.compareTo(abandonAfter) < 0) {
            throw new IllegalStateException("dokene.integration.whatsapp.webhook.inbox.retention must be at least "
                    + MIN_RETENTION + " and at least abandon-after");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "dokene.integration.whatsapp.webhook.inbox.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (inlineLimit < 0 || inlineLimit > MAX_INLINE_LIMIT) {
            throw new IllegalStateException(
                    "dokene.integration.whatsapp.webhook.inbox.inline-limit must be between 0 and " + MAX_INLINE_LIMIT);
        }
    }
}
