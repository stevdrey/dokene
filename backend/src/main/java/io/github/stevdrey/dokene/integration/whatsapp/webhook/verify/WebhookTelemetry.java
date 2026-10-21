// ABOUTME: Port the webhook route reports refusals and dropped events through; the reason is the only dimension.
// ABOUTME: Implemented by MicrometerWebhookTelemetry and NoOpWebhookTelemetry, chosen by the configuration.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;

public interface WebhookTelemetry {
    void rejected(Reason reason);

    /**
     * An acknowledged event that changed nothing (issue 139, ADR 0030); the route answers 200 either way. A default,
     * so a refusal-only telemetry stays a lambda; only the Micrometer implementation counts drops.
     */
    default void dropped(DropReason reason) {
        // Not counted unless the implementation records it.
    }

    enum DropReason { UNKNOWN_PHONE_NUMBER, DUPLICATE, UNMATCHED_MESSAGE, UNSUPPORTED_EVENT }
}
