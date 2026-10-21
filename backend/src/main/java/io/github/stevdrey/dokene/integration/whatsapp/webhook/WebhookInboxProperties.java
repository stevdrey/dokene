// ABOUTME: Webhook inbox settings under dokene.integration.whatsapp.webhook.inbox (issue 139, ADR 0030).
// ABOUTME: Validated at startup; the park window bounds unmatched reports and retention bounds dedup rows.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dokene.integration.whatsapp.webhook.inbox")
public record WebhookInboxProperties(Boolean enabled, Duration interval, Duration parkWindow, Duration retention,
        Integer batchSize) {
    static final int MAX_BATCH_SIZE = 500;
    /** Meta retries an unacknowledged callback for up to 7 days; dedup rows must outlive every retry. */
    static final Duration MIN_RETENTION = Duration.ofDays(8);

    public WebhookInboxProperties {
        enabled = enabled == null || enabled;
        interval = interval == null ? Duration.ofSeconds(30) : interval;
        parkWindow = parkWindow == null ? Duration.ofMinutes(15) : parkWindow;
        retention = retention == null ? Duration.ofDays(30) : retention;
        batchSize = batchSize == null ? 100 : batchSize;
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalStateException("dokene.integration.whatsapp.webhook.inbox.interval must be positive");
        }
        if (parkWindow.isNegative() || parkWindow.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException(
                    "dokene.integration.whatsapp.webhook.inbox.park-window must be between 0 and 1 hour");
        }
        if (retention.compareTo(MIN_RETENTION) < 0) {
            throw new IllegalStateException(
                    "dokene.integration.whatsapp.webhook.inbox.retention must be at least " + MIN_RETENTION);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "dokene.integration.whatsapp.webhook.inbox.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
    }
}
