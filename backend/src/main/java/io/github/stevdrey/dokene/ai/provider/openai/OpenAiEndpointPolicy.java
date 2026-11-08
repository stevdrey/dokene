package io.github.stevdrey.dokene.ai.provider.openai;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Endpoint policy for the OpenAI base URL. The API key and customer data are sent to this endpoint, so it must be
 * {@code https://api.openai.com}, an exact loopback test server ({@code localhost}, {@code 127.0.0.1} or
 * {@code [::1]}, over HTTP or HTTPS), or an HTTPS host explicitly listed in
 * {@code dokene.ai.openai.additional-trusted-hosts}. User info, query and fragment are never allowed. Anything else,
 * malformed input included, fails closed before a client is built, so no provider call is made.
 *
 * <p>The accepted URL is rebuilt from its validated components, so the HTTP client never parses anything this policy
 * did not check. Error messages never echo the configured value, which could carry credentials.
 */
final class OpenAiEndpointPolicy {

    static final String OPENAI_HOST = "api.openai.com";
    static final String DEFAULT_BASE_URL = "https://" + OPENAI_HOST + "/v1";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");
    private static final String REJECTED = "dokene.ai.openai.base-url must be https on " + OPENAI_HOST
            + ", a loopback test server (localhost, 127.0.0.1 or [::1]) or an https host listed in "
            + "dokene.ai.openai.additional-trusted-hosts, without credentials, query or fragment";

    private OpenAiEndpointPolicy() {
    }

    /** Validates every trusted host entry and returns an immutable copy. */
    static List<String> trustedHosts(List<String> entries) {
        if (entries == null) {
            return List.of();
        }
        for (String entry : entries) {
            // Exact, lower-case host names only: no scheme, port, path, wildcard or trailing dot.
            if (entry == null || !HOSTNAME.matcher(entry).matches()) {
                throw new IllegalArgumentException(
                        "dokene.ai.openai.additional-trusted-hosts entries must be exact lower-case host names");
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Returns the canonical base URL to configure, or throws {@link IllegalArgumentException} when the value is not
     * allowed. A {@code null} or blank value means the default OpenAI endpoint.
     */
    static String canonicalBaseUrl(String baseUrl, List<String> trustedHosts) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(REJECTED);
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        // getHost() is null for registry-based authorities (percent-encoded, non-ASCII, underscores and the like).
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || uri.isOpaque() || host == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0
                || uri.getPort() > 65535) {
            throw new IllegalArgumentException(REJECTED);
        }
        boolean https = "https".equals(scheme);
        boolean loopback = LOOPBACK_HOSTS.contains(host) && (https || "http".equals(scheme));
        boolean trusted = https && (host.equals(OPENAI_HOST) || trustedHosts.contains(host));
        if (!(loopback || trusted)) {
            throw new IllegalArgumentException(REJECTED);
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + path;
    }
}
