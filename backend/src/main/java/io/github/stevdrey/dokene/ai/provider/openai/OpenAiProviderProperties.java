package io.github.stevdrey.dokene.ai.provider.openai;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Externalized configuration for OpenAI Responses API integration.
 * API key must never be logged or defaulted to sensitive values.
 *
 * <p>{@code baseUrl} is validated by {@link OpenAiEndpointPolicy} at binding time and always holds the canonical
 * endpoint (the OpenAI default when unset). {@code additionalTrustedHosts} lists the exact HTTPS hosts, such as an
 * internal gateway the deployment controls, that may receive the API key and customer data besides
 * {@code api.openai.com}; listing a host is a trust decision and the list is empty by default.
 */
@ConfigurationProperties(prefix = "dokene.ai.openai")
public record OpenAiProviderProperties(
        String apiKey,
        String model,
        String baseUrl,
        Duration timeout,
        Integer maxRetries,
        Integer maxOutputTokens,
        List<String> additionalTrustedHosts
) {
    public static final String DEFAULT_MODEL = "gpt-6-luna";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    public static final int DEFAULT_MAX_RETRIES = 0;

    /**
     * Per-request output ceiling (reasoning tokens included) for both structured operations. About ten times the
     * observed average output (about 390 tokens in a median 6.8 s, issue 124 baseline). It caps what one attempt can
     * generate and bill; it does not stop generation after the client times out. At that rate the 15 s timeout
     * delivers roughly 850 tokens, so an abandoned attempt can still bill about 3.2k more. A response cut off here is
     * incomplete and fails as an invalid structured response with reason {@code OUTPUT_TOKEN_LIMIT}, which is never
     * retried. It is also the effective draft size limit: the draft schema allows more (about 5k tokens).
     */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;
    public static final int MAX_OUTPUT_TOKENS_FLOOR = 256;
    public static final int MAX_OUTPUT_TOKENS_CEILING = 16384;

    /** Configuration without additional trusted hosts. */
    public OpenAiProviderProperties(String apiKey, String model, String baseUrl, Duration timeout, Integer maxRetries,
            Integer maxOutputTokens) {
        this(apiKey, model, baseUrl, timeout, maxRetries, maxOutputTokens, null);
    }

    @ConstructorBinding
    public OpenAiProviderProperties {
        additionalTrustedHosts = OpenAiEndpointPolicy.trustedHosts(additionalTrustedHosts);
        baseUrl = OpenAiEndpointPolicy.canonicalBaseUrl(baseUrl, additionalTrustedHosts);
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
        } else if (maxOutputTokens < MAX_OUTPUT_TOKENS_FLOOR || maxOutputTokens > MAX_OUTPUT_TOKENS_CEILING) {
            throw new IllegalArgumentException("dokene.ai.openai.max-output-tokens must be between "
                    + MAX_OUTPUT_TOKENS_FLOOR + " and " + MAX_OUTPUT_TOKENS_CEILING);
        }
    }

    /** Redacted: the record holds the API key (audit I1). */
    @Override
    public String toString() {
        return "OpenAiProviderProperties[REDACTED]";
    }
}
