// ABOUTME: Fails application startup when dokene.messaging.provider holds an unsupported value.
// ABOUTME: Mirrors AiProviderSelectionConfiguration so a deployment typo never degrades to "no provider".
package io.github.stevdrey.dokene.integration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class MessagingProviderSelectionConfiguration {

    @Bean
    static MessagingProviderSelectionValidator messagingProviderSelectionValidator(Environment environment) {
        return new MessagingProviderSelectionValidator(
                MessagingProviderType.fromProperty(environment.getProperty(MessagingProviderType.PROPERTY)));
    }

    /** Marker holding the validated provider selection. */
    record MessagingProviderSelectionValidator(MessagingProviderType selected) {
    }
}
