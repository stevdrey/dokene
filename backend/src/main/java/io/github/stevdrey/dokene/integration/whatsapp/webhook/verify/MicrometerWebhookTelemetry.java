// ABOUTME: Micrometer WebhookTelemetry: dokene.webhook.rejected and .dropped tagged with the reason, and .overloaded.
// ABOUTME: No request field, tenant or correlation id ever becomes a tag; the reason enum is the whole vocabulary.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

public final class MicrometerWebhookTelemetry implements WebhookTelemetry {
    static final String REJECTED = "dokene.webhook.rejected";
    static final String DROPPED = "dokene.webhook.dropped";
    static final String OVERLOADED = "dokene.webhook.overloaded";

    private final MeterRegistry registry;

    public MicrometerWebhookTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "Meter registry is required");
    }

    @Override
    public void rejected(Reason reason) {
        registry.counter(REJECTED, "reason", reason.name()).increment();
    }

    @Override
    public void overloaded() {
        registry.counter(OVERLOADED).increment();
    }

    @Override
    public void dropped(DropReason reason, long count) {
        if (count > 0) {
            registry.counter(DROPPED, "reason", reason.name()).increment(count);
        }
    }
}
