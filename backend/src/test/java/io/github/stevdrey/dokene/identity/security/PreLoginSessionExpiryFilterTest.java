// ABOUTME: Unit tests of the filter that ends an abandoned login start session at the pre login timeout (issue 311):
// ABOUTME: the age boundary, signed in sessions left alone, and requests with no session or an ordinary one.
package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class PreLoginSessionExpiryFilterTest {

    private static final Duration PRE_LOGIN_TIMEOUT = Duration.ofMinutes(5);
    private static final Instant STARTED_AT = Instant.parse("2026-11-03T08:00:00Z");

    @Test
    void endsALoginStartSessionOlderThanThePreLoginTimeoutAndContinues() throws Exception {
        MockHttpSession preLogin = preLoginSession();

        assertThat(reachesTheChain(preLogin, PRE_LOGIN_TIMEOUT.plusMillis(1))).isTrue();
        assertThat(preLogin.isInvalid()).isTrue();
    }

    @Test
    void keepsALoginStartSessionWithinThePreLoginTimeout() throws Exception {
        MockHttpSession preLogin = preLoginSession();

        assertThat(reachesTheChain(preLogin, PRE_LOGIN_TIMEOUT)).isTrue();
        assertThat(preLogin.isInvalid()).isFalse();
    }

    @Test
    void neverEndsASignedInSession() throws Exception {
        MockHttpSession signedIn = preLoginSession();
        signedIn.setAttribute(BffSessionLifetimeFilter.AUTHENTICATED_AT, STARTED_AT);

        assertThat(reachesTheChain(signedIn, PRE_LOGIN_TIMEOUT.plusMinutes(1))).isTrue();
        assertThat(signedIn.isInvalid()).isFalse();
    }

    @Test
    void leavesASessionNoLoginStartCreatedAlone() throws Exception {
        MockHttpSession other = new MockHttpSession();

        assertThat(reachesTheChain(other, PRE_LOGIN_TIMEOUT.plusMinutes(1))).isTrue();
        assertThat(other.isInvalid()).isFalse();
    }

    @Test
    void neverCreatesASession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/customers");

        filterAt(PRE_LOGIN_TIMEOUT.plusMinutes(1)).doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(request.getSession(false)).isNull();
    }

    private static MockHttpSession preLoginSession() {
        MockHttpSession preLogin = new MockHttpSession();
        preLogin.setAttribute(BffSessionLifetimeFilter.PRE_LOGIN_STARTED_AT, STARTED_AT);
        return preLogin;
    }

    /** Runs one unsafe request carrying {@code session}, {@code age} after its login start. */
    private static boolean reachesTheChain(MockHttpSession session, Duration age) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/customers");
        request.setSession(session);
        AtomicBoolean reached = new AtomicBoolean();
        filterAt(age).doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
        return reached.get();
    }

    private static PreLoginSessionExpiryFilter filterAt(Duration age) {
        return new PreLoginSessionExpiryFilter(PRE_LOGIN_TIMEOUT, Clock.fixed(STARTED_AT.plus(age), ZoneOffset.UTC));
    }
}
