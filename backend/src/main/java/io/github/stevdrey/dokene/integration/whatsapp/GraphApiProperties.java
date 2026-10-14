package io.github.stevdrey.dokene.integration.whatsapp;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Meta Graph API endpoint and per-call timeout. Every tenant bearer token is sent to {@code baseUrl}, so the host is
 * restricted: {@code graph.facebook.com} over HTTPS, loopback test servers, or a host explicitly listed in
 * {@code additionalTrustedHosts}. Listing a host is a trust decision: it will receive tenant credentials, so it must
 * be an internal HTTPS proxy or mock the deployment controls, never a public third party.
 */
@ConfigurationProperties(prefix = "dokene.integration.whatsapp.graph")
public record GraphApiProperties(URI baseUrl, Duration timeout, List<String> additionalTrustedHosts) {
    public static final URI DEFAULT_BASE_URL = URI.create("https://graph.facebook.com/v23.0");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final String META_GRAPH_HOST = "graph.facebook.com";
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");

    public GraphApiProperties {
        baseUrl = baseUrl == null ? DEFAULT_BASE_URL : baseUrl;
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        additionalTrustedHosts = additionalTrustedHosts == null ? List.of() : List.copyOf(additionalTrustedHosts);
        for (String trusted : additionalTrustedHosts) {
            // Exact, lower-case hostnames only: no scheme, port, path, wildcard or IP-literal surprises.
            if (trusted == null || !HOSTNAME.matcher(trusted).matches()) {
                throw new IllegalArgumentException("Trusted Graph API hosts must be exact lower-case host names");
            }
        }
        String host = baseUrl.getHost() == null ? null : baseUrl.getHost().toLowerCase(Locale.ROOT);
        String scheme = baseUrl.getScheme();
        boolean https = "https".equals(scheme);
        boolean loopback = host != null && isLoopback(host) && ("http".equals(scheme) || https);
        boolean trusted = https && host != null
                && (host.equals(META_GRAPH_HOST) || additionalTrustedHosts.contains(host));
        if (!(loopback || trusted) || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("Graph API base URL must be https on " + META_GRAPH_HOST
                    + ", a loopback test server or an explicitly trusted host, without credentials, query or fragment");
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
