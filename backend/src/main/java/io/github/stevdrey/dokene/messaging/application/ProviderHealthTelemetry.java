// ABOUTME: Telemetry port for provider health (ADR 0038). Every argument is a closed enum, so no meter can carry a
// ABOUTME: tenant id, message id, phone number, token or payload.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthState;
import io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind;

public interface ProviderHealthTelemetry {

    enum StoreOperation {
        READ, RECORD
    }

    void signal(MessagingProviderKey provider, ProviderSignalKind kind);

    void transition(MessagingProviderKey provider, ProviderHealthState from, ProviderHealthState to);

    void storeFailure(MessagingProviderKey provider, StoreOperation operation);
}
