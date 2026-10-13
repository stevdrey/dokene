// ABOUTME: What the delivery sink did with a report: applied a transition, ignored it, or found no message.
// ABOUTME: NotFound is a value, not an exception, so the webhook can acknowledge and move on.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import java.util.Objects;

public sealed interface DeliveryStatusResult {

    record Applied(MessageStatus from, MessageStatus to) implements DeliveryStatusResult {
        public Applied {
            Objects.requireNonNull(from, "Source status is required");
            Objects.requireNonNull(to, "Target status is required");
        }
    }

    record Ignored() implements DeliveryStatusResult {
    }

    record NotFound() implements DeliveryStatusResult {
    }
}
