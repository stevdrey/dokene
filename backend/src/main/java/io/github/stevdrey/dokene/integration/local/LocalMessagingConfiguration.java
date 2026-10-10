// ABOUTME: Wires the local test provider when dokene.messaging.provider is local, exactly like the fake AI provider.
// ABOUTME: Absent otherwise, so integration tests may register a spy as the only MessagingProvider bean.
package io.github.stevdrey.dokene.integration.local;

import io.github.stevdrey.dokene.integration.MessagingProviderType;
import io.github.stevdrey.dokene.integration.ProviderReportExecution;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusSink;
import io.github.stevdrey.dokene.messaging.application.MessagingProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = MessagingProviderType.PROPERTY, havingValue = "local")
@EnableConfigurationProperties(LocalMessagingProperties.class)
public class LocalMessagingConfiguration {

    @Bean
    public LocalDeliveryScheduler localDeliveryScheduler(DeliveryStatusSink sink, ProviderReportExecution execution,
            LocalMessagingProperties properties, ObjectProvider<MeterRegistry> registry, Clock clock) {
        return new LocalDeliveryScheduler(sink, execution, properties, registry.getIfAvailable(), clock);
    }

    @Bean
    public MessagingProvider localMessagingProvider(LocalDeliveryScheduler scheduler) {
        return new LocalMessagingProvider(scheduler);
    }
}
