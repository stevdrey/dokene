// ABOUTME: Micrometer ProviderHealthTelemetry (ADR 0038): dokene.messaging.provider.health.signals, .transitions and
// ABOUTME: .store.failures, tagged only with the provider key and closed enum values.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthState;
import io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

public final class MicrometerProviderHealthTelemetry implements ProviderHealthTelemetry {
    static final String SIGNALS = "dokene.messaging.provider.health.signals";
    static final String TRANSITIONS = "dokene.messaging.provider.health.transitions";
    static final String STORE_FAILURES = "dokene.messaging.provider.health.store.failures";

    private final MeterRegistry registry;

    public MicrometerProviderHealthTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "Meter registry is required");
    }

    @Override
    public void signal(MessagingProviderKey provider, ProviderSignalKind kind) {
        registry.counter(SIGNALS, "provider", provider.name(), "kind", kind.name()).increment();
    }

    @Override
    public void transition(MessagingProviderKey provider, ProviderHealthState from, ProviderHealthState to) {
        registry.counter(TRANSITIONS, "provider", provider.name(), "from", from.name(), "to", to.name()).increment();
    }

    @Override
    public void storeFailure(MessagingProviderKey provider, StoreOperation operation) {
        registry.counter(STORE_FAILURES, "provider", provider.name(), "operation", operation.name()).increment();
    }
}
