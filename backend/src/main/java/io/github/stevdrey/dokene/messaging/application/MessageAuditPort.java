// ABOUTME: Audit port for message transitions; implemented in audit.application like the other durable adapters.
// ABOUTME: One method per audited event type; the attempt started event has no audit row by design.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.tenant.application.ProviderContext;

public interface MessageAuditPort {
    void submitted(MessageEvent event);

    void approved(MessageEvent event);

    void rejected(MessageEvent event);

    void cancelled(MessageEvent event);

    void sendRequested(MessageEvent event);

    void sent(MessageEvent event);

    void sendFailed(MessageEvent event);

    void sendOutcomeUnknown(MessageEvent event);

    /** Tenant attributed, no actor or membership; only valid under the provider context. */
    void deliveryUpdated(ProviderContext context, MessageEvent event);
}
