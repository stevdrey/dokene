// ABOUTME: Inbound port the webhook boundary calls with provider delivery facts (ADR 0023 §1).
// ABOUTME: Runs only under a trusted ProviderContext; a member context is refused.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;

public interface DeliveryStatusSink {
    DeliveryStatusResult apply(DeliveryStatusReport report);
}
