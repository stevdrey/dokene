package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.http.ProviderEndpointPolicy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Meta Graph API endpoint and per-call timeout. Every tenant bearer token is sent to {@code baseUrl}, so it must pass
 * the shared {@link ProviderEndpointPolicy} with {@code graph.facebook.com} as the provider host and
 * {@code additionalTrustedHosts} as the trusted-host list; the bound value is the canonical URL the policy returns.
 * Listing a host is a trust decision: it will receive tenant credentials, so it must be an internal HTTPS proxy or
 * mock the deployment controls, never a public third party.
 */
@ConfigurationProperties(prefix = "dokene.integration.whatsapp.graph")
public record GraphApiProperties(URI baseUrl, Duration timeout, List<String> additionalTrustedHosts) {
    public static final URI DEFAULT_BASE_URL = URI.create("https://graph.facebook.com/v23.0");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final String META_GRAPH_HOST = "graph.facebook.com";
    private static final ProviderEndpointPolicy POLICY = new ProviderEndpointPolicy(META_GRAPH_HOST,
            "dokene.integration.whatsapp.graph.base-url",
            "dokene.integration.whatsapp.graph.additional-trusted-hosts");

    public GraphApiProperties {
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        additionalTrustedHosts = POLICY.trustedHosts(additionalTrustedHosts);
        baseUrl = baseUrl == null ? DEFAULT_BASE_URL
                : URI.create(POLICY.canonicalBaseUrl(baseUrl.toString(), additionalTrustedHosts));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Graph API timeout must be positive");
        }
    }
}
