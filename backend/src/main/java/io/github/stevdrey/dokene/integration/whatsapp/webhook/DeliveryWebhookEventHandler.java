// ABOUTME: The only integration class allowed to reach DeliveryStatusSink (ADR 0024 §3): hands it a verified report.
// ABOUTME: The caller binds the ProviderContext for the event's tenant; this class only translates the sink's result.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import io.github.stevdrey.dokene.integration.application.VerifiedWebhookEvent;
import io.github.stevdrey.dokene.integration.application.VerifiedWebhookEventHandler;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusResult;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusSink;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DeliveryWebhookEventHandler implements VerifiedWebhookEventHandler {
    private final DeliveryStatusSink sink;
    private final ProviderContextProvider providerContexts;

    public DeliveryWebhookEventHandler(DeliveryStatusSink sink, ProviderContextProvider providerContexts) {
        this.sink = Objects.requireNonNull(sink);
        this.providerContexts = Objects.requireNonNull(providerContexts);
    }

    @Override
    public Handling handle(VerifiedWebhookEvent event) {
        Objects.requireNonNull(event, "Event is required");
        // The trusted scope must name the tenant the verified mapping resolved; anything else is a wiring defect.
        if (!providerContexts.requireCurrent().tenantId().equals(event.tenantId())) {
            throw new IllegalStateException("Provider context does not match the event tenant");
        }
        return switch (sink.apply(event.report())) {
            case DeliveryStatusResult.Applied applied -> Handling.APPLIED;
            case DeliveryStatusResult.Ignored ignored -> Handling.IGNORED;
            case DeliveryStatusResult.NotFound notFound -> Handling.NOT_FOUND;
        };
    }
}
