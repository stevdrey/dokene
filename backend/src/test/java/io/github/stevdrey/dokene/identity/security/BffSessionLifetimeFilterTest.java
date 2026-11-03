package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

@ExtendWith(OutputCaptureExtension.class)
class BffSessionLifetimeFilterTest {

    private static final Duration MAX_LIFETIME = Duration.ofHours(8);
    private static final Duration INTERVAL = Duration.ofMinutes(5);
    private static final Duration GRACE = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-03T08:00:00Z"));
    private final HttpSessionOAuth2AuthorizedClientRepository authorizedClients =
            new HttpSessionOAuth2AuthorizedClientRepository();
    private final AtomicReference<RefreshBehavior> refresh = new AtomicReference<>(RefreshBehavior.SUCCEED);
    private final AtomicInteger refreshCalls = new AtomicInteger();
    /** How long the provider takes to answer a refresh call. */
    private final AtomicReference<Duration> refreshDuration = new AtomicReference<>(Duration.ZERO);
    private final BffSessionLifetimeFilter filter = new BffSessionLifetimeFilter(
            new BffSessionProperties(MAX_LIFETIME, INTERVAL, GRACE, null), authorizedClients, this::respond, clock);
    private final ClientRegistration registration = ClientRegistration.withRegistrationId("dokene")
            .clientId("dokene-bff")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("https://idp.test/authorize")
            .tokenUri("https://idp.test/token")
            .build();

    private MockHttpSession session;
    private OAuth2AuthenticationToken authentication;

    @BeforeEach
    void login() {
        session = new MockHttpSession();
        authentication = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.NO_AUTHORITIES, Map.of("sub", "user"), "sub"),
                AuthorityUtils.NO_AUTHORITIES, "dokene");
        MockHttpServletRequest request = request();
        authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, "user",
                accessToken("initial-access"), new OAuth2RefreshToken("initial-refresh", clock.instant())),
                authentication, request, new MockHttpServletResponse());
        filter.startSession(session);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void keepsAFreshSessionWithoutCallingTheProvider() throws Exception {
        clock.advance(INTERVAL.minusSeconds(1));

        assertThat(run()).isTrue();
        assertThat(refreshCalls).hasValue(0);
    }

    @Test
    void revalidatesAfterTheIntervalAndStoresTheRotatedTokens() throws Exception {
        clock.advance(INTERVAL);

        assertThat(run()).isTrue();
        assertThat(refreshCalls).hasValue(1);
        assertThat(session.getAttribute(BffSessionLifetimeFilter.LAST_REVALIDATED_AT)).isEqualTo(clock.instant());
        OAuth2AuthorizedClient stored = authorizedClients.loadAuthorizedClient("dokene", authentication, request());
        assertThat(stored.getAccessToken().getTokenValue()).isEqualTo("refreshed-access");
        assertThat(stored.getRefreshToken().getTokenValue()).isEqualTo("refreshed-refresh");

        clock.advance(INTERVAL.minusSeconds(1));
        assertThat(run()).isTrue();
        assertThat(refreshCalls).hasValue(1);
    }

    @Test
    void revokesWhenTheProviderRejectsTheRefreshToken(CapturedOutput output) throws Exception {
        refresh.set(RefreshBehavior.INVALID_GRANT);
        clock.advance(INTERVAL);

        assertThat(run()).isFalse();
        assertThat(session.isInvalid()).isTrue();
        assertThat(output.getAll()).contains("PROVIDER_REJECTED").doesNotContain("initial-refresh", "initial-access");
    }

    @Test
    void revokesWhenTheSessionHasNoRefreshToken() throws Exception {
        authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, "user",
                accessToken("initial-access")), authentication, request(), new MockHttpServletResponse());
        clock.advance(INTERVAL);

        assertThat(run()).isFalse();
        assertThat(refreshCalls).hasValue(0);
    }

    @Test
    void keepsTheSessionWhileTheProviderIsUnavailableWithinTheGrace() throws Exception {
        refresh.set(RefreshBehavior.UNAVAILABLE);
        clock.advance(INTERVAL);
        assertThat(run()).isTrue();

        // Every request retries; the session still ends once the grace has passed since the last success.
        clock.advance(GRACE.minus(INTERVAL).minusSeconds(1));
        assertThat(run()).isTrue();
        assertThat(refreshCalls).hasValue(2);

        clock.advance(Duration.ofSeconds(1));
        assertThat(run()).isFalse();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void recoversWhenTheProviderReturnsWithinTheGrace() throws Exception {
        refresh.set(RefreshBehavior.UNAVAILABLE);
        clock.advance(INTERVAL);
        assertThat(run()).isTrue();

        refresh.set(RefreshBehavior.SUCCEED);
        clock.advance(INTERVAL);
        assertThat(run()).isTrue();

        refresh.set(RefreshBehavior.UNAVAILABLE);
        clock.advance(GRACE.minusSeconds(1));
        assertThat(run()).isTrue();
    }

    @Test
    void endsAnActiveSessionAtTheAbsoluteLifetime() throws Exception {
        for (Duration elapsed = Duration.ZERO; elapsed.compareTo(MAX_LIFETIME) < 0; elapsed = elapsed.plus(INTERVAL)) {
            assertThat(run()).isTrue();
            clock.advance(INTERVAL);
        }

        assertThat(run()).isFalse();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void revokesAnOidcSessionWithoutALifetimeRecord() throws Exception {
        session.removeAttribute(BffSessionLifetimeFilter.AUTHENTICATED_AT);

        assertThat(run()).isFalse();
    }

    @Test
    void ignoresSessionsThatWereNotEstablishedByOidcLogin() throws Exception {
        MockHttpSession other = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/session");
        request.setSession(other);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "user", null, List.of()));

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(other.isInvalid()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void judgesTheGraceOnTheClockAfterASlowRefreshCall() throws Exception {
        refresh.set(RefreshBehavior.UNAVAILABLE);
        refreshDuration.set(Duration.ofSeconds(2));
        clock.advance(GRACE.minusSeconds(1));

        assertThat(run()).isFalse();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void judgesTheLifetimeOnTheClockAfterASlowRefreshCall() throws Exception {
        refreshDuration.set(Duration.ofSeconds(2));
        clock.advance(MAX_LIFETIME.minusSeconds(1));

        assertThat(run()).isFalse();
        assertThat(refreshCalls).hasValue(1);
    }

    @Test
    void recordsTheRevalidationAtTheStartOfTheRefreshCall() throws Exception {
        refreshDuration.set(Duration.ofSeconds(2));
        clock.advance(INTERVAL);
        Instant started = clock.instant();

        assertThat(run()).isTrue();
        assertThat(session.getAttribute(BffSessionLifetimeFilter.LAST_REVALIDATED_AT)).isEqualTo(started);
    }

    @Test
    void rechecksTheLifetimeAfterWaitingForTheSessionLock() throws Exception {
        clock.advance(INTERVAL);
        CompletableFuture<Boolean> queued;
        // Another request holds the session's revalidation lock while this one queues behind it.
        synchronized (session) {
            AtomicReference<Thread> worker = new AtomicReference<>();
            queued = CompletableFuture.supplyAsync(() -> {
                worker.set(Thread.currentThread());
                try {
                    return run();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            while (worker.get() == null || worker.get().getState() != Thread.State.BLOCKED) {
                Thread.onSpinWait();
            }
            clock.advance(MAX_LIFETIME);
        }

        assertThat(queued.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(refreshCalls).hasValue(0);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void rejectsInconsistentProperties() {
        assertThatThrownBy(() -> new BffSessionProperties(MAX_LIFETIME, INTERVAL, INTERVAL, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revalidation-grace");
        assertThatThrownBy(() -> new BffSessionProperties(Duration.ofMinutes(10), INTERVAL, GRACE, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-lifetime");
        assertThatThrownBy(() -> new BffSessionProperties(Duration.ZERO, INTERVAL, GRACE, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BffSessionProperties(MAX_LIFETIME, Duration.ZERO, GRACE, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BffSessionProperties(MAX_LIFETIME, INTERVAL, GRACE, INTERVAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revalidation-timeout");
        assertThatThrownBy(() -> new BffSessionProperties(MAX_LIFETIME, INTERVAL, GRACE, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new BffSessionProperties(null, null, null, null)).isEqualTo(new BffSessionProperties(
                Duration.ofHours(8), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofSeconds(5)));
    }

    /** Runs one request through the filter and reports whether it reached the chain authenticated. */
    private boolean run() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        AtomicReference<Boolean> authenticated = new AtomicReference<>();
        filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> authenticated.set(
                SecurityContextHolder.getContext().getAuthentication() != null));
        return authenticated.get();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/session");
        if (!session.isInvalid()) {
            request.setSession(session);
        }
        return request;
    }

    private OAuth2AccessTokenResponse respond(OAuth2RefreshTokenGrantRequest grant) {
        refreshCalls.incrementAndGet();
        clock.advance(refreshDuration.get());
        assertThat(grant.getRefreshToken().getTokenValue()).isIn("initial-refresh", "refreshed-refresh");
        return switch (refresh.get()) {
            case SUCCEED -> OAuth2AccessTokenResponse.withToken("refreshed-access")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .expiresIn(300)
                    .refreshToken("refreshed-refresh")
                    .build();
            case INVALID_GRANT -> throw new OAuth2AuthorizationException(new OAuth2Error("invalid_grant"));
            case UNAVAILABLE -> throw new OAuth2AuthorizationException(new OAuth2Error("invalid_token_response"));
        };
    }

    private OAuth2AccessToken accessToken(String value) {
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value, clock.instant(),
                clock.instant().plusSeconds(300));
    }

    private enum RefreshBehavior {
        SUCCEED, INVALID_GRANT, UNAVAILABLE
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

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
