package io.github.stevdrey.dokene.ai.provider.openai;

import io.github.stevdrey.dokene.http.ProviderEndpointPolicy;
import java.util.List;

/**
 * Endpoint policy for the OpenAI base URL. The API key and customer data are sent to this endpoint, so it must pass
 * the shared {@link ProviderEndpointPolicy} with {@code api.openai.com} as the provider host and
 * {@code dokene.ai.openai.additional-trusted-hosts} as the trusted-host list. An unset value means the default
 * OpenAI endpoint; anything refused fails closed before a client is built, so no provider call is made.
 */
final class OpenAiEndpointPolicy {

    static final String OPENAI_HOST = "api.openai.com";
    static final String DEFAULT_BASE_URL = "https://" + OPENAI_HOST + "/v1";
    private static final ProviderEndpointPolicy POLICY = new ProviderEndpointPolicy(OPENAI_HOST,
            "dokene.ai.openai.base-url", "dokene.ai.openai.additional-trusted-hosts");

    private OpenAiEndpointPolicy() {
    }

    /** Validates every trusted host entry and returns an immutable copy. */
    static List<String> trustedHosts(List<String> entries) {
        return POLICY.trustedHosts(entries);
    }

    /**
     * Returns the canonical base URL to configure, or throws {@link IllegalArgumentException} when the value is not
     * allowed. A {@code null} or blank value means the default OpenAI endpoint.
     */
    static String canonicalBaseUrl(String baseUrl, List<String> trustedHosts) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        return POLICY.canonicalBaseUrl(baseUrl, trustedHosts);
    }
}
