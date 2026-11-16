// ABOUTME: Refuses startup on the settings that would let a request shape a redirect (spec 0019, issue 318): a
// ABOUTME: leftover redirect URI, a second registration, an unfixed replay URI, a context path, the native strategy.
package io.github.stevdrey.dokene.identity.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties.ForwardHeadersStrategy;

/**
 * The strategy and replay URI checks always run. The origin, redirect URI, registration id and context path checks
 * run when a client registration is configured, which is exactly when Boot binds {@link OAuth2ClientProperties}; binding covers
 * every relaxed spelling of a setting, and an unset redirect URI stays null there.
 */
public final class RedirectOriginStartupValidator {

    static final String REGISTRATION_ID = "dokene";
    static final List<String> REQUEST_DERIVED_PLACEHOLDERS =
            List.of("{baseUrl}", "{baseScheme}", "{baseHost}", "{basePort}", "{basePath}");

    private static final String BACK_CHANNEL_LOGOUT_SETTING =
            "dokene.security.oidc.back-channel-logout-uri (DOKENE_BACK_CHANNEL_LOGOUT_URI)";

    private final PublicOrigin origin;
    private final String backChannelLogoutUri;

    RedirectOriginStartupValidator(PublicOriginSettings settings, OAuth2ClientProperties clientProperties,
            ForwardHeadersStrategy forwardHeadersStrategy, String contextPath, String backChannelLogoutUri) {
        requireSupportedForwardHeadersStrategy(forwardHeadersStrategy);
        requireFixedBackChannelLogoutUri(backChannelLogoutUri);
        if (clientProperties != null) {
            this.origin = settings.requiredOrigin();
            requireOnlyTheDokeneRegistration(clientProperties.getRegistration());
            requireNoLeftoverRedirectUri(origin,
                    clientProperties.getRegistration().get(REGISTRATION_ID).getRedirectUri());
            requireRootContextPath(contextPath);
        } else {
            this.origin = null;
        }
        this.backChannelLogoutUri = backChannelLogoutUri;
    }

    /** The validated origin a client registration's callback is derived from. */
    PublicOrigin registrationOrigin() {
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
        for (String placeholder : REQUEST_DERIVED_PLACEHOLDERS) {
            if (uri.contains(placeholder)) {
                throw new IllegalStateException(BACK_CHANNEL_LOGOUT_SETTING + " must not contain the request derived "
                        + "placeholder " + placeholder + "; write it as a fixed URI");
            }
        }
        if (!isAbsoluteHttpUriWithHost(uri)) {
            throw new IllegalStateException(BACK_CHANNEL_LOGOUT_SETTING + " must be an absolute http:// or https:// "
                    + "URI with a host");
        }
    }

    /** {@code {registrationId}} is the one placeholder left; it is not a legal URI character, so it is filled first. */
    private static boolean isAbsoluteHttpUriWithHost(String uri) {
        try {
            URI parsed = new URI(uri.replace("{registrationId}", REGISTRATION_ID));
            return ("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))
                    && parsed.getHost() != null && !parsed.getHost().isEmpty();
        } catch (URISyntaxException malformed) {
            return false;
        }
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
