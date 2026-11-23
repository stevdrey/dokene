// ABOUTME: Refuses startup on the settings that would let a request shape a redirect (spec 0019, issue 318): a
// ABOUTME: leftover redirect URI, a second registration, an unfixed replay URI, a context path, the native strategy.
package io.github.stevdrey.dokene.identity.security;

import io.github.stevdrey.dokene.http.LoopbackHosts;
import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties.ForwardHeadersStrategy;

/**
 * The strategy check always runs, and the replay URI checks run whenever the server has a listener (with
 * {@code server.port=-1} it has none, so nothing is received or replayed). The origin, redirect URI, registration
 * id and context path checks run when a client registration is configured, which is exactly when Boot binds
 * {@link OAuth2ClientProperties}; binding covers every relaxed spelling of a setting, and an unset redirect URI
 * stays null there.
 */
public final class RedirectOriginStartupValidator {

    static final String REGISTRATION_ID = "dokene";
    static final int DEFAULT_SERVER_PORT = 8080;

    private static final String BACK_CHANNEL_LOGOUT_SETTING =
            "dokene.security.oidc.back-channel-logout-uri (DOKENE_BACK_CHANNEL_LOGOUT_URI)";

    /** A host name {@link URI} gives no host for, such as one with an underscore, and its optional port. */
    private static final Pattern NAME_AUTHORITY = Pattern.compile("([A-Za-z0-9._-]+)(?::([0-9]{1,5}))?");

    private final PublicOrigin origin;
    private final String backChannelLogoutUri;

    RedirectOriginStartupValidator(PublicOriginSettings settings, OAuth2ClientProperties clientProperties,
            ForwardHeadersStrategy forwardHeadersStrategy, String contextPath, boolean tlsListener,
            Integer configuredServerPort, String backChannelLogoutUri) {
        int serverPort = configuredServerPort == null ? DEFAULT_SERVER_PORT : configuredServerPort;
        // A negative server.port keeps the web context with no listener; the default would read http://localhost:-1.
        boolean listens = serverPort >= 0;
        requireSupportedForwardHeadersStrategy(forwardHeadersStrategy);
        if (listens) {
            requireFixedBackChannelLogoutUri(backChannelLogoutUri);
        }
        if (clientProperties != null) {
            // Present: the settings refuse an unset origin on the same registration signal.
            this.origin = settings.origin().orElseThrow();
            if (listens) {
                requireReplayUriReachesTheListener(backChannelLogoutUri, tlsListener, serverPort);
            }
            requireOnlyTheDokeneRegistration(clientProperties.getRegistration());
            requireNoLeftoverRedirectUri(origin,
                    clientProperties.getRegistration().get(REGISTRATION_ID).getRedirectUri());
            requireRootContextPath(contextPath);
        } else {
            this.origin = null;
        }
        this.backChannelLogoutUri = backChannelLogoutUri;
    }

    /** The validated origin a client registration's callback and logout return address are derived from. */
    public PublicOrigin registrationOrigin() {
        if (origin == null) {
            throw new IllegalStateException("A client registration repository exists, but no OAuth client "
                    + "registration is configured under spring.security.oauth2.client.registration");
        }
        return origin;
    }

    /** Where the back channel logout handler replays each session's logout. */
    public String backChannelLogoutUri() {
        return backChannelLogoutUri;
    }

    static void requireSupportedForwardHeadersStrategy(ForwardHeadersStrategy strategy) {
        if (strategy != ForwardHeadersStrategy.NONE && strategy != ForwardHeadersStrategy.FRAMEWORK) {
            throw new IllegalStateException("server.forward-headers-strategy (SERVER_FORWARD_HEADERS_STRATEGY) must be "
                    + "none or framework; native and an unset or blank value are refused");
        }
    }

    static void requireFixedBackChannelLogoutUri(String uri) {
        // An empty override resolves to "", not to the default, and Spring would then replay to a relative path.
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException(BACK_CHANNEL_LOGOUT_SETTING + " must not be blank; unset it to use the "
                    + "loopback default, or write a fixed absolute URI");
        }
        // Spring fills {registrationId} without the request; {baseUrl}, {basePort} and the like, in any spelling
        // such as {basePort:\d*}, read the request, and nothing else has a value at all.
        String filled = uri.replace("{registrationId}", REGISTRATION_ID);
        if (filled.indexOf('{') >= 0 || filled.indexOf('}') >= 0) {
            throw new IllegalStateException(BACK_CHANNEL_LOGOUT_SETTING + " must not contain a placeholder other "
                    + "than {registrationId}; write it as a fixed URI");
        }
        if (replayTarget(uri) == null) {
            throw new IllegalStateException(BACK_CHANNEL_LOGOUT_SETTING + " must be an absolute http:// or https:// "
                    + "URI with a host");
        }
    }

    /**
     * The default follows the configured port and plain http, not the listener actually bound, and a replay that
     * reaches no listener fails without stopping anything: sessions would outlive a provider logout. Checked with a
     * registration only, because without one nothing is replayed and a test may start on a random port. A loopback
     * override must name the configured port for the same reason; another host is the operator's to get right.
     */
    static void requireReplayUriReachesTheListener(String uri, boolean tlsListener, int serverPort) {
        ReplayTarget target = replayTarget(uri);
        if (target.port() == 0) {
            throw new IllegalStateException(BACK_CHANNEL_LOGOUT_SETTING + " must not use port 0: with server.port "
                    + "(SERVER_PORT) set to 0 the server picks its port after this URI is built; set a fixed "
                    + "SERVER_PORT, or write the replay URI with the real port");
        }
        boolean https = "https".equals(target.scheme());
        if (tlsListener && !https) {
            throw new IllegalStateException(BACK_CHANNEL_LOGOUT_SETTING + " must start with https:// while server.ssl "
                    + "is enabled: the listener does not answer the plain http replay of the default; write it "
                    + "with a host name the listener's certificate carries, or the replay fails the TLS handshake");
        }
        int replayPort = target.port() >= 0 ? target.port() : https ? 443 : 80;
        // The wide rule on purpose: it only decides whether the port must match, so any loopback spelling counts.
        if (LoopbackHosts.reachesLoopback(target.host()) && replayPort != serverPort) {
            throw new IllegalStateException(BACK_CHANNEL_LOGOUT_SETTING + " points at loopback port " + replayPort
                    + " while server.port (SERVER_PORT) is " + serverPort + ": no listener answers that replay; "
                    + "write the replay URI with the port the server listens on, or unset it to use the default");
        }
    }

    /** Port -1 when the URI leaves it out. */
    private record ReplayTarget(String scheme, String host, int port) {
    }

    /**
     * The scheme, host and port of an absolute http or https replay URI, or null when it is not one.
     * {@code {registrationId}} is the one placeholder left; it is not a legal URI character, so it is filled first.
     */
    private static ReplayTarget replayTarget(String uri) {
        URI parsed;
        try {
            parsed = URI.create(uri.replace("{registrationId}", REGISTRATION_ID));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        String scheme = parsed.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        if (parsed.getHost() != null) {
            return parsed.getHost().isEmpty() ? null : new ReplayTarget(scheme, parsed.getHost(), parsed.getPort());
        }
        // URI reports no host and no port for a name it does not accept as a server, such as dokene_backend,
        // while Spring's own URI handling replays to it; the authority still holds both.
        Matcher authority = NAME_AUTHORITY.matcher(parsed.getAuthority() == null ? "" : parsed.getAuthority());
        if (!authority.matches()) {
            return null;
        }
        return new ReplayTarget(scheme, authority.group(1),
                authority.group(2) == null ? -1 : Integer.parseInt(authority.group(2)));
    }

    /** The callback path is fixed at {@code /login/oauth2/code/}, so under a context path it would miss the app. */
    static void requireRootContextPath(String contextPath) {
        if (contextPath != null && !contextPath.isEmpty() && !contextPath.equals("/")) {
            throw new IllegalStateException("server.servlet.context-path (SERVER_SERVLET_CONTEXT_PATH) must be unset "
                    + "while an OAuth client registration is configured: the login callback is "
                    + "DOKENE_PUBLIC_ORIGIN plus a fixed path; serve a path prefix at the edge instead");
        }
    }

    static void requireOnlyTheDokeneRegistration(Map<String, OAuth2ClientProperties.Registration> registrations) {
        for (String id : registrations.keySet()) {
            if (!id.equals(REGISTRATION_ID)) {
                throw new IllegalStateException("The backend supports only the \"dokene\" client registration; "
                        + "remove spring.security.oauth2.client.registration." + id);
            }
        }
        if (!registrations.containsKey(REGISTRATION_ID)) {
            throw new IllegalStateException("The backend supports only the \"dokene\" client registration, and it "
                    + "is not configured under spring.security.oauth2.client.registration.dokene");
        }
    }

    static void requireNoLeftoverRedirectUri(PublicOrigin origin, String redirectUri) {
        if (redirectUri == null
                || redirectUri.equals(origin.loginCallbackTemplate())
                || redirectUri.equals(origin.loginCallback(REGISTRATION_ID))) {
            return;
        }
        throw new IllegalStateException("spring.security.oauth2.client.registration.dokene.redirect-uri "
                + "(SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_REDIRECT_URI) is derived from the public "
                + "origin; remove it and set DOKENE_PUBLIC_ORIGIN instead");
    }
}
