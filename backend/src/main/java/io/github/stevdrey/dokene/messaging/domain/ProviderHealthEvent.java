// ABOUTME: Everything that can change a provider health row (ADR 0038): a send signal, a probe result, or the gate
// ABOUTME: finding the row stale. ProviderHealthPolicy is the only code that turns one into a new row.
package io.github.stevdrey.dokene.messaging.domain;

import java.util.Objects;

public sealed interface ProviderHealthEvent {

    /** Closed name for meters and log lines. */
    String label();

    record SendSignal(ProviderSignalKind kind) implements ProviderHealthEvent {
        public SendSignal {
            Objects.requireNonNull(kind, "Signal kind is required");
        }

        @Override
        public String label() {
            return "SIGNAL_" + kind.name();
        }
    }

    record ProbeFinished(ProviderProbeResult result) implements ProviderHealthEvent {
        public ProbeFinished {
            Objects.requireNonNull(result, "Probe result is required");
        }

        @Override
        public String label() {
            return "PROBE_" + result.name();
        }
    }

    /** The gate found a HEALTHY or DEGRADED row with no signal for {@code stale-after}. */
    record WentStale() implements ProviderHealthEvent {
        @Override
        public String label() {
            return "STALE";
        }
    }
}
