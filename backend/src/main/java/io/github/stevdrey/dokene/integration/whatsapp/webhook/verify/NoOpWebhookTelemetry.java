// ABOUTME: WebhookTelemetry for a context without a MeterRegistry; refusals are still logged by the refusing class.
// ABOUTME: Chosen by WhatsAppWebhookConfiguration exactly as AiRuntimeConfiguration chooses its no op telemetry.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;

public final class NoOpWebhookTelemetry implements WebhookTelemetry {
    @Override
    public void rejected(Reason reason) {
        // Nothing to record without a registry.
    }
}
