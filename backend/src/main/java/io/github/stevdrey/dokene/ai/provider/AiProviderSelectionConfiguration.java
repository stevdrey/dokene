package io.github.stevdrey.dokene.ai.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fails application startup when {@code dokene.ai.provider} holds an unsupported value, so a
 * deployment typo cannot silently degrade to the disabled provider.
 */
@Configuration(proxyBeanMethods = false)
public class AiProviderSelectionConfiguration {

    @Bean
    static AiProviderSelectionValidator aiProviderSelectionValidator(Environment environment) {
        return new AiProviderSelectionValidator(
                AiProviderType.fromProperty(environment.getProperty(AiProviderType.PROPERTY)));
    }

    /** Marker holding the validated provider selection. */
    record AiProviderSelectionValidator(AiProviderType selected) {
    }
}
