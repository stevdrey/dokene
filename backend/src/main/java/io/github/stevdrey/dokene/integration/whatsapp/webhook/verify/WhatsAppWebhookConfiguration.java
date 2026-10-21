// ABOUTME: Wires the webhook verifier, challenge check, bounded reader and telemetry as plain beans (spec 0007 AC-10).
// ABOUTME: Validates the two refs and the body bound at startup, naming only the property; a blank ref means refuse.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WhatsAppWebhookProperties.class)
public class WhatsAppWebhookConfiguration {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookConfiguration.class);
    static final String VERIFY_TOKEN_REF = "dokene.integration.whatsapp.verify-token-ref";
    static final String APP_SECRET_REF = "dokene.integration.whatsapp.app-secret-ref";
    static final String MAX_BODY_BYTES = "dokene.integration.whatsapp.webhook.max-body-bytes";
    static final long DEFAULT_MAX_BODY_BYTES = 4_194_304L;
    static final long MIN_MAX_BODY_BYTES = 1L;
    static final long MAX_MAX_BODY_BYTES = 16_777_216L;

    @Bean
    WebhookTelemetry webhookTelemetry(ObjectProvider<MeterRegistry> registry) {
        MeterRegistry meterRegistry = registry.getIfAvailable();
        return meterRegistry == null ? new NoOpWebhookTelemetry() : new MicrometerWebhookTelemetry(meterRegistry);
    }

    @Bean
    WebhookSignatureVerifier webhookSignatureVerifier(IntegrationSecretResolver resolver,
            WhatsAppWebhookProperties properties, WebhookTelemetry telemetry) {
        return new WebhookSignatureVerifier(resolver, reference(APP_SECRET_REF, properties.appSecretRef()),
                telemetry);
    }

    @Bean
    WebhookChallengeVerifier webhookChallengeVerifier(IntegrationSecretResolver resolver,
            WhatsAppWebhookProperties properties, WebhookTelemetry telemetry) {
        return new WebhookChallengeVerifier(resolver, reference(VERIFY_TOKEN_REF, properties.verifyTokenRef()),
                telemetry);
    }

    @Bean
    BoundedWebhookBodyReader boundedWebhookBodyReader(WhatsAppWebhookProperties properties,
            WebhookTelemetry telemetry) {
        return new BoundedWebhookBodyReader(bound(properties.webhook().maxBodyBytes()), telemetry);
    }

    /** Blank is the refusing state, warned once here; a set value must match the grammar untrimmed (AC-6). */
    private static Optional<SecretReference> reference(String property, String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("Webhook verification refusing: {} is unset", property);
            return Optional.empty();
        }
        if (!SecretReference.FORMAT.matcher(raw).matches()) {
            // The value never reaches the message: it may be a secret pasted where a reference belongs.
            throw new IllegalStateException(property + " is not a valid secret reference");
        }
        return Optional.of(new SecretReference(raw));
    }

    private static int bound(Long raw) {
        long value = raw == null ? DEFAULT_MAX_BODY_BYTES : raw;
        if (value < MIN_MAX_BODY_BYTES || value > MAX_MAX_BODY_BYTES) {
            throw new IllegalStateException(MAX_BODY_BYTES + " must be between " + MIN_MAX_BODY_BYTES + " and "
                    + MAX_MAX_BODY_BYTES);
        }
        return (int) value;
    }
}
