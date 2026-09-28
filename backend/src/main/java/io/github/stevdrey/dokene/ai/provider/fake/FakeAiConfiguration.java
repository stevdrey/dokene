package io.github.stevdrey.dokene.ai.provider.fake;

import io.github.stevdrey.dokene.ai.application.AiProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures {@link DefaultFakeAiProvider} when {@code dokene.ai.provider}
 * is explicitly set to {@code fake} and no other {@link AiProvider} bean is defined.
 */
@Configuration(proxyBeanMethods = false)
public class FakeAiConfiguration {

    @Bean
    @ConditionalOnProperty(name = "dokene.ai.provider", havingValue = "fake", matchIfMissing = false)
    @ConditionalOnMissingBean(AiProvider.class)
    public AiProvider defaultFakeAiProvider() {
        return new DefaultFakeAiProvider();
    }
}
