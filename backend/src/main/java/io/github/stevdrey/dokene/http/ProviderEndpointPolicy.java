// ABOUTME: The one endpoint allow-list for outbound provider base URLs (security invariant 29, issue 440): the
// ABOUTME: provider host over HTTPS, an exact loopback test server, or an exactly listed HTTPS host, rebuilt canonically.
package io.github.stevdrey.dokene.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Endpoint policy shared by every provider that receives credentials or customer data (the Meta Graph API and
 * OpenAI). A base URL is accepted only when it is:
 *
 * <ul>
 *   <li>{@code https} on {@link #providerHost()};</li>
 *   <li>{@code http} or {@code https} on an exact loopback spelling ({@link LoopbackHosts#isExactSpelling}), never a
 *       name under {@code localhost} or another spelling of a loopback address; or</li>
 *   <li>{@code https} on a host listed exactly in the trusted-host list, which is itself validated by
 *       {@link TrustedHostNames}.</li>
 * </ul>
 *
 * The scheme and host are compared in lower case. User info, query and fragment are refused even when empty, and
 * so are port {@code 0} and ports above {@code 65535}. Anything else, malformed input included, fails closed with an
 * {@link IllegalArgumentException} before a client is built.
 *
 * <p>The accepted URL is rebuilt from its validated components (lower-case scheme and host, dot segments removed and
 * repeated slashes in the path collapsed by {@link URI#normalize()}, default port dropped), so the HTTP client never
 * parses anything this policy did not check; a path that still climbs above the root after normalization is refused.
 * Messages name the
 * configuration properties and never echo the configured value, which could carry credentials.
 *
 * @param providerHost the provider's own host, accepted over HTTPS
 * @param baseUrlProperty the configuration property that holds the base URL, named in refusal messages
 * @param trustedHostsProperty the configuration property that lists additional trusted hosts
 */
public record ProviderEndpointPolicy(String providerHost, String baseUrlProperty, String trustedHostsProperty) {

    /** Fails fast on a provider host that could never match, since parsed hosts are compared in lower case. */
    public ProviderEndpointPolicy {
        Objects.requireNonNull(baseUrlProperty, "baseUrlProperty");
        Objects.requireNonNull(trustedHostsProperty, "trustedHostsProperty");
        if (!TrustedHostNames.isExactHostName(providerHost)) {
            throw new IllegalArgumentException("providerHost must be an exact lower-case host name");
        }
    }

    /** Validates every trusted host entry and returns an immutable copy; {@code null} means none. */
    public List<String> trustedHosts(List<String> entries) {
        if (entries == null) {
            return List.of();
        }
        for (String entry : entries) {
            // Exact, lower-case host names only: no scheme, port, path, wildcard, trailing dot or IP literal.
            if (!TrustedHostNames.isExactHostName(entry)) {
                throw new IllegalArgumentException(
                        trustedHostsProperty + " entries must be exact lower-case host names");
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Returns the canonical form of {@code baseUrl}, or throws {@link IllegalArgumentException} when it is not
     * allowed. {@code trustedHosts} must already have passed {@link #trustedHosts(List)}. A {@code null} or blank
     * value is refused; callers apply their own default first.
     */
    public String canonicalBaseUrl(String baseUrl, List<String> trustedHosts) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw rejected();
        }
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw rejected();
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        // getHost() is null for registry-based authorities (percent-encoded, non-ASCII, underscores and the like).
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        // Raw components: an empty or percent-encoded user info, query or fragment is refused like any other.
        if (!uri.isAbsolute() || uri.isOpaque() || host == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0
                || uri.getPort() > 65535) {
            throw rejected();
        }
        boolean https = "https".equals(scheme);
        boolean http = "http".equals(scheme);
        // The exact rule on purpose: an allow-list for credentials never accepts a name under localhost (issue 428).
        boolean loopback = LoopbackHosts.isExactSpelling(host) && (https || http);
        boolean trusted = https && (host.equals(providerHost) || trustedHosts.contains(host));
        if (!(loopback || trusted)) {
            throw rejected();
        }
        String path = uri.normalize().getRawPath();
        // normalize() keeps a ".." that climbs above the root; such a path has no canonical form, so it is refused.
        if (path != null && (path.equals("/..") || path.startsWith("/../"))) {
            throw rejected();
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (https && port == 443) || (http && port == 80);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port) + (path == null ? "" : path);
    }

    private IllegalArgumentException rejected() {
        return new IllegalArgumentException(baseUrlProperty + " must be https on " + providerHost
                + ", a loopback test server (" + LoopbackHosts.EXACT_SPELLINGS_TEXT + ") or an https host listed in "
                + trustedHostsProperty + ", without credentials, query, fragment or an out-of-range port");
    }
}
