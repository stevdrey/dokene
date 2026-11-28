// ABOUTME: Wires the admission budget (ADR 0040): binds and validates dokene.admission, builds the one policy from it
// ABOUTME: and declares the telemetry, which counts only when a meter registry exists.
package io.github.stevdrey.dokene.admission;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AdmissionProperties.class)
class AdmissionConfiguration {

    @Bean
    AdmissionPolicy admissionPolicy(AdmissionProperties properties) {
        return new AdmissionPolicy(properties);
    }

    @Bean
    AdmissionTelemetry admissionTelemetry(ObjectProvider<MeterRegistry> registry, Clock clock) {
        return new AdmissionTelemetry(registry.getIfAvailable(), clock);
    }
}
