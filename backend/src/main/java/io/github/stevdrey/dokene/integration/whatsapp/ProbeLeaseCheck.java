// ABOUTME: Startup check that the provider health probe lease outlasts one Graph call (ADR 0038), so a probe that is
// ABOUTME: still inside its own deadline can never lose its lease to a second probe.
package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.messaging.application.ProviderHealthProperties;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code dokene.messaging.provider-health.probe-lease} must be strictly longer than
 * {@code dokene.integration.whatsapp.graph.timeout}, which bounds the probe. The rule spans two property records, so
 * neither can check it alone. A misconfiguration fails startup rather than letting two probes overlap.
 */
@Component
class ProbeLeaseCheck {
    ProbeLeaseCheck(GraphApiProperties graph, ProviderHealthProperties health) {
        require(Objects.requireNonNull(graph, "Graph properties are required").timeout(),
                Objects.requireNonNull(health, "Provider health properties are required").probeLease());
    }

    static void require(Duration graphTimeout, Duration probeLease) {
        if (probeLease.compareTo(graphTimeout) <= 0) {
            throw new IllegalStateException("dokene.messaging.provider-health.probe-lease (" + probeLease
                    + ") must be longer than dokene.integration.whatsapp.graph.timeout (" + graphTimeout + ")");
        }
    }
}
