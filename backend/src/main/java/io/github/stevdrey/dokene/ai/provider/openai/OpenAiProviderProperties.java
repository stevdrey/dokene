package io.github.stevdrey.dokene.ai.provider.openai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        Integer maxRetries,
        Integer maxOutputTokens
) {
    public static final String DEFAULT_MODEL = "gpt-6-luna";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    public static final int DEFAULT_MAX_RETRIES = 0;

    /**
     * Per-request output ceiling (reasoning tokens included) for both structured operations. About ten times the
     * observed average output, and above what the timeout lets a client receive; the provider stops and bills at
     * this point instead of generating after the client has given up. A response cut off here is incomplete and
     * fails as an invalid structured response, which is never retried.
     */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;
    public static final int MIN_MAX_OUTPUT_TOKENS = 256;
    public static final int MAX_MAX_OUTPUT_TOKENS = 16384;

    @ConstructorBinding
    public OpenAiProviderProperties {
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = DEFAULT_TIMEOUT;
        }
        if (maxRetries == null) {
            maxRetries = DEFAULT_MAX_RETRIES;
        } else if (maxRetries != 0) {
            throw new IllegalArgumentException(
                    "dokene.ai.openai.max-retries must be 0 to enforce single-invocation timeout determinism; "
                            + "retries are owned by the domain-level decorator configured with dokene.ai.retry.*");
        }
        if (maxOutputTokens == null) {
            maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
        } else if (maxOutputTokens < MIN_MAX_OUTPUT_TOKENS || maxOutputTokens > MAX_MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("dokene.ai.openai.max-output-tokens must be between "
                    + MIN_MAX_OUTPUT_TOKENS + " and " + MAX_MAX_OUTPUT_TOKENS);
        }
    }

    public OpenAiProviderProperties(String apiKey, String model, String baseUrl, Duration timeout, Integer maxRetries) {
        this(apiKey, model, baseUrl, timeout, maxRetries, null);
    }
}
