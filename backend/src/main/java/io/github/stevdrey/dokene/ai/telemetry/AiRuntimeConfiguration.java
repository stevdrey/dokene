package io.github.stevdrey.dokene.ai.telemetry;

import io.github.stevdrey.dokene.ai.application.AiResilience;
import io.github.stevdrey.dokene.ai.application.AiRetryProperties;
import io.github.stevdrey.dokene.ai.application.AiTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires AI telemetry and the retry/telemetry decorator factory. No actuator endpoint is exposed. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiRetryProperties.class)
public class AiRuntimeConfiguration {

    @Bean
    AiTelemetry aiTelemetry(ObjectProvider<MeterRegistry> registry) {
        MeterRegistry meterRegistry = registry.getIfAvailable();
        return meterRegistry == null ? AiTelemetry.noop() : new MicrometerAiTelemetry(meterRegistry);
    }

    @Bean
    AiResilience aiResilience(AiRetryProperties retry, AiTelemetry telemetry) {
        return new AiResilience(retry, telemetry);
    }
}
