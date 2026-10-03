package io.github.stevdrey.dokene.ai.provider.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.github.stevdrey.dokene.ai.application.AiCorrelationSource;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing a singleton {@link OpenAIClient} and {@link OpenAiResponsesApiAdapter}
 * when {@code dokene.ai.provider} is configured to {@code openai}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiProviderProperties.class)
public class OpenAiConfiguration {

    @Bean
    @ConditionalOnProperty(name = "dokene.ai.provider", havingValue = "openai")
    public OpenAIClient openAiClient(OpenAiProviderProperties properties) {
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key must be configured when dokene.ai.provider is set to 'openai'");
        }
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey);
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            builder.baseUrl(properties.baseUrl());
        }
        if (properties.timeout() != null) {
            builder.timeout(properties.timeout());
        }
        if (properties.maxRetries() != null && properties.maxRetries() != 0) {
            throw new IllegalArgumentException(
                    "dokene.ai.openai.max-retries must be 0 to enforce single-invocation timeout determinism; "
                            + "retries are owned by the domain-level decorator configured with dokene.ai.retry.*");
        }
        builder.maxRetries(0);
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "dokene.ai.provider", havingValue = "openai")
    public AiProvider openAiProvider(OpenAIClient openAiClient, OpenAiProviderProperties properties,
            ObjectProvider<AiCorrelationSource> correlation) {
        return new OpenAiResponsesApiAdapter(openAiClient, properties,
                correlation.getIfAvailable(AiCorrelationSource::none));
    }
}
