// ABOUTME: Micrometer WebhookTelemetry: one counter, dokene.webhook.rejected, tagged with the closed reason name.
// ABOUTME: No request field, tenant or correlation id ever becomes a tag; the reason enum is the whole vocabulary.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

public final class MicrometerWebhookTelemetry implements WebhookTelemetry {
    static final String REJECTED = "dokene.webhook.rejected";

    private final MeterRegistry registry;

    public MicrometerWebhookTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "Meter registry is required");
    }

    @Override
    public void rejected(Reason reason) {
        registry.counter(REJECTED, "reason", reason.name()).increment();
    }
}
