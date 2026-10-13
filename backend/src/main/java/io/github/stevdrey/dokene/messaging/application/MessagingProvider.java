// ABOUTME: Outbound port the integration module implements to send one approved message (ADR 0023 §1).
// ABOUTME: Provider exceptions never cross it; the adapter normalizes them into ProviderSendResult.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;

public interface MessagingProvider {
    ProviderSendResult send(OutboundSendCommand command);
}
