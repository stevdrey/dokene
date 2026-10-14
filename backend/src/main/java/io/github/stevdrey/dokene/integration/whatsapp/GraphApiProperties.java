package io.github.stevdrey.dokene.integration.whatsapp;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Meta Graph API endpoint and per-call timeout; defaults are the public endpoint and a short bound. */
@ConfigurationProperties(prefix = "dokene.integration.whatsapp.graph")
public record GraphApiProperties(URI baseUrl, Duration timeout) {
    public static final URI DEFAULT_BASE_URL = URI.create("https://graph.facebook.com/v23.0");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    public GraphApiProperties {
        baseUrl = baseUrl == null ? DEFAULT_BASE_URL : baseUrl;
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        // Every tenant bearer token travels to this endpoint: HTTPS only, plain HTTP just for loopback test servers.
        String host = baseUrl.getHost();
        boolean https = "https".equals(baseUrl.getScheme());
        boolean loopbackHttp = "http".equals(baseUrl.getScheme()) && host != null && isLoopback(host);
        if (!(https || loopbackHttp) || host == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Graph API base URL must be https (http only for loopback) without credentials, query or fragment");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Graph API timeout must be positive");
        }
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || host.endsWith(".localhost") || host.equals("127.0.0.1")
                || host.equals("[::1]");
    }
}
