// ABOUTME: Bound shape of the webhook verifier settings under dokene.integration.whatsapp (spec 0007 AC-10).
// ABOUTME: Refs are bare keys, never secrets, and still every toString is redacted so a bind failure prints nothing.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dokene.integration.whatsapp")
public record WhatsAppWebhookProperties(String verifyTokenRef, String appSecretRef, Webhook webhook) {
    public WhatsAppWebhookProperties {
        if (webhook == null) {
            webhook = new Webhook(null);
        }
    }

    /** Null means the 4 MiB default; the configuration enforces the range at startup. */
    public record Webhook(Long maxBodyBytes) {
        @Override
        public String toString() {
            return "WhatsAppWebhookProperties.Webhook[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "WhatsAppWebhookProperties[REDACTED]";
    }
}
