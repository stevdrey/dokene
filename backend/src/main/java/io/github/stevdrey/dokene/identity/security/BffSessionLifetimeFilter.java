// ABOUTME: Ends OIDC browser sessions at an absolute lifetime and when the provider stops honouring them (issue 307).
// ABOUTME: Periodically redeems the session's refresh token; a rejected refresh means a disabled user or ended session.
package io.github.stevdrey.dokene.identity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

/**
 * Runs after the security context is loaded and before authorization. A session it revokes is invalidated and the
 * request continues unauthenticated, so {@code /api/**} fails closed with {@code 401} exactly like an idle-expired
 * session. Sessions not established by OIDC login are left alone.
 */
public final class BffSessionLifetimeFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_AT = BffSessionLifetimeFilter.class.getName() + ".AUTHENTICATED_AT";
    public static final String LAST_REVALIDATED_AT = BffSessionLifetimeFilter.class.getName() + ".LAST_REVALIDATED_AT";

    private static final Logger log = LoggerFactory.getLogger(BffSessionLifetimeFilter.class);

    private final BffSessionProperties properties;
    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenClient;
    private final Clock clock;

    public BffSessionLifetimeFilter(
            BffSessionProperties properties,
            OAuth2AuthorizedClientRepository authorizedClients,
            OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenClient,
            Clock clock
    ) {
        this.properties = Objects.requireNonNull(properties, "Session properties are required");
        this.authorizedClients = Objects.requireNonNull(authorizedClients, "Authorized client repository is required");
        this.refreshTokenClient = Objects.requireNonNull(refreshTokenClient, "Refresh token client is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /** Starts the lifetime and revalidation clocks; called once the authenticated session id is in place. */
    public void startSession(HttpSession session) {
        Instant now = clock.instant();
        session.setAttribute(AUTHENTICATED_AT, now);
        session.setAttribute(LAST_REVALIDATED_AT, now);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (session != null && authentication instanceof OAuth2AuthenticationToken token) {
            try {
                Revocation revocation = check(session, token, request, response);
                if (revocation != null) {
                    revoke(session, revocation);
                }
            } catch (IllegalStateException alreadyInvalidated) {
                // A concurrent request ended this session first; the request must not stay authenticated.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private Revocation check(HttpSession session, OAuth2AuthenticationToken token, HttpServletRequest request,
            HttpServletResponse response) {
        Instant now = clock.instant();
        if (lifetimeExceeded(session, now)) {
            return Revocation.MAX_LIFETIME;
        }
        if (isFresh(session, now)) {
            return null;
        }
        synchronized (WebUtils.getSessionMutex(session)) {
            // Waiting for the lock can outlast several revalidation calls: every deadline is judged on the clock as it
            // is now, not as it was when the request arrived. Another request may also have revalidated meanwhile.
            Instant started = clock.instant();
            if (lifetimeExceeded(session, started)) {
                return Revocation.MAX_LIFETIME;
            }
            if (isFresh(session, started)) {
                return null;
            }
            // A session without a revalidation record gets no grace.
            Instant lastRevalidatedAt = Objects.requireNonNullElse(instant(session, LAST_REVALIDATED_AT), Instant.EPOCH);
            Refresh outcome = refresh(token, request, response);
            Instant finished = clock.instant();
            if (lifetimeExceeded(session, finished)) {
                return Revocation.MAX_LIFETIME;
            }
            return switch (outcome) {
                case REFRESHED -> {
                    session.setAttribute(LAST_REVALIDATED_AT, started);
                    yield null;
                }
                case REJECTED -> Revocation.PROVIDER_REJECTED;
                case UNAVAILABLE -> finished.isBefore(lastRevalidatedAt.plus(properties.revalidationGrace()))
                        ? null
                        : Revocation.REVALIDATION_GRACE_EXCEEDED;
            };
        }
    }

    private boolean lifetimeExceeded(HttpSession session, Instant now) {
        Instant authenticatedAt = instant(session, AUTHENTICATED_AT);
        return authenticatedAt == null || !now.isBefore(authenticatedAt.plus(properties.maxLifetime()));
    }

    private boolean isFresh(HttpSession session, Instant now) {
        Instant lastRevalidatedAt = instant(session, LAST_REVALIDATED_AT);
        return lastRevalidatedAt != null && now.isBefore(lastRevalidatedAt.plus(properties.revalidationInterval()));
    }

    private Refresh refresh(OAuth2AuthenticationToken token, HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizedClient client = authorizedClients.loadAuthorizedClient(
                token.getAuthorizedClientRegistrationId(), token, request);
        if (client == null || client.getRefreshToken() == null) {
            return Refresh.REJECTED;
        }
        try {
            OAuth2AccessTokenResponse tokens = refreshTokenClient.getTokenResponse(new OAuth2RefreshTokenGrantRequest(
                    client.getClientRegistration(), client.getAccessToken(), client.getRefreshToken()));
            OAuth2RefreshToken refreshToken = tokens.getRefreshToken() != null
                    ? tokens.getRefreshToken()
                    : client.getRefreshToken();
            authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(client.getClientRegistration(),
                    client.getPrincipalName(), tokens.getAccessToken(), refreshToken), token, request, response);
            return Refresh.REFRESHED;
        } catch (OAuth2AuthorizationException exception) {
            if (OAuth2ErrorCodes.INVALID_GRANT.equals(exception.getError().getErrorCode())) {
                return Refresh.REJECTED;
            }
            // Only the error code: provider descriptions are untrusted text and may echo request data.
            log.warn("BFF session revalidation failed: {}", exception.getError().getErrorCode());
            return Refresh.UNAVAILABLE;
        } catch (RuntimeException exception) {
            log.warn("BFF session revalidation failed: {}", exception.getClass().getSimpleName());
            return Refresh.UNAVAILABLE;
        }
    }

    private void revoke(HttpSession session, Revocation revocation) {
        log.info("BFF session revoked: {}", revocation);
        SecurityContextHolder.clearContext();
        session.invalidate();
    }

    private static Instant instant(HttpSession session, String name) {
        return session.getAttribute(name) instanceof Instant value ? value : null;
    }

    private enum Refresh {
        REFRESHED, REJECTED, UNAVAILABLE
    }

    private enum Revocation {
        MAX_LIFETIME, PROVIDER_REJECTED, REVALIDATION_GRACE_EXCEEDED
    }
}
