// ABOUTME: Startup check that the Graph send deadline fits inside the dispatcher's provider call deadline (ADR 0027),
// ABOUTME: so a slow but definitive Meta answer is read by the adapter instead of being cut off as an unknown outcome.
package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.messaging.application.MessagingDispatchProperties;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The adapter's own timeout ({@code dokene.integration.whatsapp.graph.timeout}) must stay strictly below the dispatch
 * deadline ({@code dokene.messaging.send-timeout}), which also covers the adapter's preparation and secret
 * resolution. Otherwise a 4xx refusal that arrives late, which is retryable or definitive, would be recorded as an
 * unknown outcome that needs a human. A misconfiguration fails startup rather than degrading silently.
 */
@Component
class DispatchDeadlineCheck {
    DispatchDeadlineCheck(GraphApiProperties graph, MessagingDispatchProperties dispatch) {
        require(Objects.requireNonNull(graph, "Graph properties are required").timeout(),
                Objects.requireNonNull(dispatch, "Dispatch properties are required").sendTimeout());
    }

    static void require(Duration graphTimeout, Duration sendTimeout) {
        if (graphTimeout.compareTo(sendTimeout) >= 0) {
            throw new IllegalStateException("dokene.integration.whatsapp.graph.timeout (" + graphTimeout
                    + ") must be shorter than dokene.messaging.send-timeout (" + sendTimeout + ")");
        }
    }
}
