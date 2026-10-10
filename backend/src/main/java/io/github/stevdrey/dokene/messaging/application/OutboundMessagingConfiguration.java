// ABOUTME: Binds the typed dokene.messaging send properties so the pipeline and the adapters share one record.
// ABOUTME: Nothing else lives here; the stand in gates bind their own stub properties.
package io.github.stevdrey.dokene.messaging.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboundMessagingProperties.class)
public class OutboundMessagingConfiguration {
}
