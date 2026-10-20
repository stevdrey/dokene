// ABOUTME: Port the webhook verifier reports refusals through; the reason is the only dimension (spec 0007 AC-8).
// ABOUTME: Implemented by MicrometerWebhookTelemetry and NoOpWebhookTelemetry, chosen by the configuration.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;

public interface WebhookTelemetry {
    void rejected(Reason reason);
}
