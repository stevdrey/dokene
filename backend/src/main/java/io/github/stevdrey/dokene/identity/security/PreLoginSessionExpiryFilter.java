// ABOUTME: Ends a login start session that is older than the pre login timeout (issue 311, audit M2), whatever the
// ABOUTME: request that carries its cookie. Runs before Spring Security's filter, so before every security chain.
package io.github.stevdrey.dokene.identity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * An idle timeout alone gives no bound: any request with the cookie resets it. This filter therefore has to see every
 * such request, so it is registered as a servlet filter ahead of Spring Security's. Inside the BFF chain it would miss
 * a request the webhook chain serves, and behind the CSRF filter it would miss an unsafe request without a token,
 * which that filter answers itself. The request continues without the session it ended.
 */
public final class PreLoginSessionExpiryFilter extends OncePerRequestFilter {

    private final Duration preLoginTimeout;
    private final Clock clock;

    public PreLoginSessionExpiryFilter(Duration preLoginTimeout, Clock clock) {
        this.preLoginTimeout = Objects.requireNonNull(preLoginTimeout, "Pre login timeout is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /**
     * Whether {@code session} was created by a login start that never completed and is now older than
     * {@code preLoginTimeout}.
     */
    public static boolean isExpiredPreLoginSession(HttpSession session, Duration preLoginTimeout, Instant now) {
        return !(session.getAttribute(BffSessionLifetimeFilter.AUTHENTICATED_AT) instanceof Instant)
                && session.getAttribute(BffSessionLifetimeFilter.PRE_LOGIN_STARTED_AT) instanceof Instant startedAt
                && startedAt.plus(preLoginTimeout).isBefore(now);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                if (isExpiredPreLoginSession(session, preLoginTimeout, clock.instant())) {
                    session.invalidate();
                }
            } catch (IllegalStateException alreadyInvalidated) {
                // A concurrent request ended this session first.
            }
        }
        chain.doFilter(request, response);
    }
}
