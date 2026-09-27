package io.github.stevdrey.dokene.ai.provider.openai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for OpenAI Responses API integration.
 * API key must never be logged or defaulted to sensitive values.
 */
@ConfigurationProperties(prefix = "dokene.ai.openai")
public record OpenAiProviderProperties(
        String apiKey,
        String model,
        String baseUrl,
        Duration timeout,
        Integer maxRetries
) {
    public static final String DEFAULT_MODEL = "gpt-6-luna";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    public static final int DEFAULT_MAX_RETRIES = 0;

    public OpenAiProviderProperties {
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = DEFAULT_TIMEOUT;
        }
        if (maxRetries == null || maxRetries < 0) {
            maxRetries = DEFAULT_MAX_RETRIES;
        }
    }
}
