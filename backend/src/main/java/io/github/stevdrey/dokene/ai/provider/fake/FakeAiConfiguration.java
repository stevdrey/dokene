package io.github.stevdrey.dokene.ai.provider.fake;

import io.github.stevdrey.dokene.ai.application.AiProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a fallback {@link DefaultFakeAiProvider} when {@code dokene.ai.provider}
 * is set to {@code fake} or when no other {@link AiProvider} bean is defined.
 */
@Configuration(proxyBeanMethods = false)
public class FakeAiConfiguration {

    @Bean
    @ConditionalOnProperty(name = "dokene.ai.provider", havingValue = "fake", matchIfMissing = true)
    @ConditionalOnMissingBean(AiProvider.class)
    public AiProvider defaultFakeAiProvider() {
        return new DefaultFakeAiProvider();
    }
}
