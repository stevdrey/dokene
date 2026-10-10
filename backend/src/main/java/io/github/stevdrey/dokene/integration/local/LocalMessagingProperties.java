// ABOUTME: Settings of the local test provider; only the delay before each simulated delivery report.
// ABOUTME: Zero by default so tests do not wait; compose sets a few seconds so QA can watch the transitions.
package io.github.stevdrey.dokene.integration.local;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dokene.messaging.local")
public record LocalMessagingProperties(Duration deliveryDelay) {
    public LocalMessagingProperties {
        deliveryDelay = deliveryDelay == null || deliveryDelay.isNegative() ? Duration.ZERO : deliveryDelay;
    }
}
