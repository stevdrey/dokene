// ABOUTME: Unit tests of the OAuth authorization request store of the BFF chain (issue 311): a login start session
// ABOUTME: is short lived and keeps its first start time, and a signed in session is never shortened.
package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class PreLoginAuthorizationRequestRepositoryTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final Instant START = Instant.parse("2026-11-10T08:00:00Z");
    private static final int NORMAL_IDLE_SECONDS = 1800;

    private final MutableClock clock = new MutableClock(START);
    private final PreLoginAuthorizationRequestRepository repository =
            new PreLoginAuthorizationRequestRepository(TIMEOUT, clock);
    private final MockHttpSession session = new MockHttpSession();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void aLoginStartMarksTheSessionAndShortensItsIdleTimeout() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        repository.saveAuthorizationRequest(authorizationRequest("state-1"), request, response);

        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute(BffSessionLifetimeFilter.PRE_LOGIN_STARTED_AT))
                .isEqualTo(START);
        assertThat(request.getSession(false).getMaxInactiveInterval()).isEqualTo(300);
    }

    @Test
    void aRepeatedLoginStartKeepsTheFirstStartTime() {
        repository.saveAuthorizationRequest(authorizationRequest("state-1"), requestWithSession(), response);
        clock.advance(Duration.ofMinutes(4));

        repository.saveAuthorizationRequest(authorizationRequest("state-2"), requestWithSession(), response);

        assertThat(session.getAttribute(BffSessionLifetimeFilter.PRE_LOGIN_STARTED_AT)).isEqualTo(START);
        assertThat(repository.loadAuthorizationRequest(callback("state-2"))).isNotNull();
    }

    @Test
    void aLoginStartOnASignedInSessionKeepsItsIdleTimeout() {
        session.setMaxInactiveInterval(NORMAL_IDLE_SECONDS);
        session.setAttribute(BffSessionLifetimeFilter.AUTHENTICATED_AT, START);

        repository.saveAuthorizationRequest(authorizationRequest("state-1"), requestWithSession(), response);

        assertThat(session.getMaxInactiveInterval()).isEqualTo(NORMAL_IDLE_SECONDS);
        assertThat(session.getAttribute(BffSessionLifetimeFilter.PRE_LOGIN_STARTED_AT)).isNull();
    }

    @Test
    void savingNullCreatesNoSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        repository.saveAuthorizationRequest(null, request, response);

        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void aCallbackGetsItsAuthorizationRequestAndNeverEndsTheSession() {
        repository.saveAuthorizationRequest(authorizationRequest("state-1"), requestWithSession(), response);
        clock.advance(Duration.ofHours(1));

        assertThat(repository.loadAuthorizationRequest(callback("state-1"))).isNotNull();
        assertThat(repository.removeAuthorizationRequest(callback("state-1"), response)).isNotNull();
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void aSessionWhoseSignedInMarkerIsNotAnInstantIsTreatedAsALoginStartSession() {
        session.setMaxInactiveInterval(NORMAL_IDLE_SECONDS);
        session.setAttribute(BffSessionLifetimeFilter.AUTHENTICATED_AT, "not an instant");

        repository.saveAuthorizationRequest(authorizationRequest("state-1"), requestWithSession(), response);

        assertThat(session.getMaxInactiveInterval()).isEqualTo(300);
        assertThat(session.getAttribute(BffSessionLifetimeFilter.PRE_LOGIN_STARTED_AT)).isEqualTo(START);
    }

    @Test
    void aCallbackWithoutASessionFindsNothingAndCreatesNoSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("state", "state-1");

        assertThat(repository.removeAuthorizationRequest(request, response)).isNull();
        assertThat(request.getSession(false)).isNull();
    }

    private MockHttpServletRequest requestWithSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }

    private MockHttpServletRequest callback(String state) {
        MockHttpServletRequest request = requestWithSession();
        request.setParameter("state", state);
        return request;
    }

    private static OAuth2AuthorizationRequest authorizationRequest(String state) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://idp.test/authorize")
                .clientId("dokene-bff")
                .state(state)
                .build();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
