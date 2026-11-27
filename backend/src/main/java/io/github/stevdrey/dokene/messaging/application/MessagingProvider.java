// ABOUTME: Outbound port the integration module implements to send one approved message (ADR 0023 §1).
// ABOUTME: Provider exceptions never cross it; the adapter normalizes them into ProviderSendResult.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind;
import java.util.Objects;
import java.util.Optional;

public interface MessagingProvider {
    ProviderSendResult send(OutboundSendCommand command);

    /**
     * One send together with what its exchange said about the provider's health (ADR 0038). {@code signal} is empty
     * when no connection was tried or the call was cut short locally, so nothing was learned about the provider.
     */
    record Exchange(ProviderSendResult result, Optional<ProviderSignalKind> signal) {
        public Exchange {
            Objects.requireNonNull(result, "Send result is required");
            Objects.requireNonNull(signal, "Signal option is required");
        }
    }

    /** As {@link #send}. An adapter that talks to a real provider overrides this to classify the signal. */
    default Exchange exchange(OutboundSendCommand command) {
        return new Exchange(send(command), Optional.empty());
    }
}
