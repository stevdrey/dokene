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
        if (baseUrl.getHost() == null || !("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("Graph API base URL must be an http(s) URL");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Graph API timeout must be positive");
        }
    }
}
