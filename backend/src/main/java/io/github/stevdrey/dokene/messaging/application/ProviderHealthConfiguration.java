// ABOUTME: Wires provider health tracking (ADR 0038): binds and validates dokene.messaging.provider-health, builds
// ABOUTME: the one policy from it, and declares the telemetry and the state gauge.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy.RefusalReason;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthSettings.Mode;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthState;
import io.github.stevdrey.dokene.messaging.domain.ProviderProbeResult;
import io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProviderHealthProperties.class)
class ProviderHealthConfiguration {

    @Bean
    ProviderHealthPolicy providerHealthPolicy(ProviderHealthProperties properties) {
        return new ProviderHealthPolicy(properties.settings());
    }

    @Bean
    ProviderHealthTelemetry providerHealthTelemetry(ObjectProvider<MeterRegistry> registry) {
        MeterRegistry meterRegistry = registry.getIfAvailable();
        if (meterRegistry != null) {
            return new MicrometerProviderHealthTelemetry(meterRegistry);
        }
        return new ProviderHealthTelemetry() {
            @Override
            public void signal(MessagingProviderKey provider, ProviderSignalKind kind) {
            }

            @Override
            public void transition(MessagingProviderKey provider, ProviderHealthState from, ProviderHealthState to) {
            }

            @Override
            public void storeFailure(MessagingProviderKey provider, StoreOperation operation) {
            }

            @Override
            public void decision(MessagingProviderKey provider, Mode mode, GateDecision decision) {
            }

            @Override
            public void refusal(MessagingProviderKey provider, RefusalReason reason) {
            }

            @Override
            public void probe(MessagingProviderKey provider, ProviderProbeResult result) {
            }
        };
    }

    /** A meter binder: Spring Boot binds it to the registry when one exists, and it is inert otherwise. */
    @Bean
    ProviderHealthStateGauge providerHealthStateGauge(ProviderHealthReader reader) {
        return new ProviderHealthStateGauge(reader, MessagingProviderKey.META_WHATSAPP);
    }
}
