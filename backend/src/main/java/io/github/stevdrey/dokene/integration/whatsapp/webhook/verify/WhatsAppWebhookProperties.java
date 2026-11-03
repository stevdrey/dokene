// ABOUTME: Bound shape of the webhook verifier settings under dokene.integration.whatsapp (spec 0007 AC-10).
// ABOUTME: Refs are bare keys, never secrets, and still every toString is redacted so a bind failure prints nothing.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dokene.integration.whatsapp")
public record WhatsAppWebhookProperties(String verifyTokenRef, String appSecretRef, Webhook webhook) {
    public WhatsAppWebhookProperties {
        if (webhook == null) {
            webhook = new Webhook(null, null);
        }
    }

    /** Null means the default (4 MiB, 8 concurrent requests); the configuration enforces the ranges at startup. */
    public record Webhook(Long maxBodyBytes, Integer maxConcurrentRequests) {
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
