// ABOUTME: OAuth authorization request store of the BFF chain (issue 311, audit M2): the session store, plus a short
// ABOUTME: life for the session a login start creates, so abandoned logins cannot hold session memory for long.
package io.github.stevdrey.dokene.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * The login start is the one anonymous request that needs a session: it holds the state, nonce and PKCE verifier. Until
 * the login succeeds that session idles out after the pre login timeout and also ends at that age, whatever its use.
 * {@link PreLoginSessionExpiryFilter} is the one place that ends it: it runs ahead of this repository on every
 * request, so a callback that arrives later finds no session and fails. This class only marks the session. The start
 * time is set once: a repeated login start must not move it, or a client repeating login starts would keep the
 * session past its age. A signed in session is never shortened here. This class never creates a session itself.
 */
public final class PreLoginAuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private final HttpSessionOAuth2AuthorizationRequestRepository delegate =
            new HttpSessionOAuth2AuthorizationRequestRepository();
    private final Duration preLoginTimeout;
    private final Clock clock;

    public PreLoginAuthorizationRequestRepository(Duration preLoginTimeout, Clock clock) {
        this.preLoginTimeout = Objects.requireNonNull(preLoginTimeout, "Pre login timeout is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return delegate.loadAuthorizationRequest(request);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
            HttpServletResponse response) {
        delegate.saveAuthorizationRequest(authorizationRequest, request, response);
        if (authorizationRequest == null) {
            return;
        }
        HttpSession session = request.getSession(false);
        if (session != null && !PreLoginSessionExpiryFilter.isSignedIn(session)) {
            if (session.getAttribute(BffSessionLifetimeFilter.PRE_LOGIN_STARTED_AT) == null) {
                session.setAttribute(BffSessionLifetimeFilter.PRE_LOGIN_STARTED_AT, clock.instant());
            }
            session.setMaxInactiveInterval(Math.toIntExact(preLoginTimeout.toSeconds()));
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        return delegate.removeAuthorizationRequest(request, response);
    }
}
