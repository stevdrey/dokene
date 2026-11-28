// ABOUTME: Telemetry port for provider health (ADR 0038). Every argument is a closed enum, so no meter can carry a
// ABOUTME: tenant id, message id, phone number, token or payload.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy.RefusalReason;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthSettings.Mode;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthState;
import io.github.stevdrey.dokene.messaging.domain.ProviderProbeResult;
import io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind;

public interface ProviderHealthTelemetry {

    enum StoreOperation {
        READ, RECORD, LEASE, RESULT
    }

    /** What the gate decided for one send. In observe mode it is what enforce would have decided. */
    enum GateDecision {
        ALLOW, REFUSE, PROBE
    }

    void signal(MessagingProviderKey provider, ProviderSignalKind kind);

    void transition(MessagingProviderKey provider, ProviderHealthState from, ProviderHealthState to);

    void storeFailure(MessagingProviderKey provider, StoreOperation operation);

    void decision(MessagingProviderKey provider, Mode mode, GateDecision decision);

    void refusal(MessagingProviderKey provider, RefusalReason reason);

    void probe(MessagingProviderKey provider, ProviderProbeResult result);
}
