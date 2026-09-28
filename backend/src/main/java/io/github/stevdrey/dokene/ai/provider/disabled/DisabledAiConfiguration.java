package io.github.stevdrey.dokene.ai.provider.disabled;

import io.github.stevdrey.dokene.ai.application.AiProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Fallback configuration providing a {@link DisabledAiProvider} when no concrete
 * {@link AiProvider} bean is defined or configured. This allows the application to boot normally
 * and execute deterministic follow-up workflows when AI is not configured.
 */
@Configuration(proxyBeanMethods = false)
public class DisabledAiConfiguration {

    @Bean
    @Conditional(NoAiProviderCondition.class)
    @ConditionalOnMissingBean(AiProvider.class)
    public AiProvider disabledAiProvider() {
        return new DisabledAiProvider();
    }
}
