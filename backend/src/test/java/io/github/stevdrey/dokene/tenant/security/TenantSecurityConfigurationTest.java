package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.identity.security.BffSessionLifetimeFilter;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class TenantSecurityConfigurationTest {

    @Test
    void trimsConfiguredCorsOriginsAndDropsBlankEntries() {
        CorsConfigurationSource source = new TenantSecurityConfiguration().corsConfigurationSource(List.of(
                "http://frontend-a.example.test",
                " http://frontend-b.example.test ",
                " "
        ));

        CorsConfiguration configuration = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/session"));

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly(
                "http://frontend-a.example.test",
                "http://frontend-b.example.test"
        );
        assertThat(configuration.getAllowedHeaders()).contains("Idempotency-Key", "If-Match", "If-None-Match");
    }

    @Test
    void exposesTheCorrelationHeaderToBrowserClients() {
        CorsConfigurationSource source = new TenantSecurityConfiguration()
                .corsConfigurationSource(List.of("http://frontend-a.example.test"));

        CorsConfiguration configuration = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/session"));

        assertThat(configuration.getExposedHeaders()).contains("ETag", "X-Request-Id");
    }

    @Test
    void emptyCorsOriginsConfiguresNoAllowedOriginsRejectingCrossOriginRequests() {
        CorsConfigurationSource source = new TenantSecurityConfiguration().corsConfigurationSource(List.of(" ", ""));

        CorsConfiguration configuration = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/session"));

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).isEmpty();
        assertThat(configuration.checkOrigin("https://malicious.example.test")).isNull();
    }

    @Test
    void preservesExplicitlyConfiguredPostLoginFailureRedirectUrl() {
        String resolved = TenantSecurityConfiguration.resolvePostLoginFailureRedirectUrl(
                "https://app.dokene.test/custom-error?source=oidc",
                "https://app.dokene.test/"
        );
        assertThat(resolved).isEqualTo("https://app.dokene.test/custom-error?source=oidc");
    }

    @Test
    void derivesPostLoginFailureUrlFromRootRedirect() {
        String resolved = TenantSecurityConfiguration.resolvePostLoginFailureRedirectUrl(
                "",
                "/"
        );
        assertThat(resolved).isEqualTo("/?error=login_failed");
    }

    @Test
    void derivesPostLoginFailureUrlFromLocalViteDevUrl() {
        String resolved = TenantSecurityConfiguration.resolvePostLoginFailureRedirectUrl(
                "   ",
                "http://localhost:5173/"
        );
        assertThat(resolved).isEqualTo("http://localhost:5173/?error=login_failed");
    }

    @Test
    void derivesPostLoginFailureUrlWhenPostLoginUrlLacksTrailingSlash() {
        String resolved = TenantSecurityConfiguration.resolvePostLoginFailureRedirectUrl(
                null,
                "http://localhost:5173"
        );
        assertThat(resolved).isEqualTo("http://localhost:5173/?error=login_failed");
    }

    @Test
    void derivesPostLoginFailureUrlAppendingToExistingQueryParams() {
        String resolved = TenantSecurityConfiguration.resolvePostLoginFailureRedirectUrl(
                null,
                "http://localhost:5173/app?lang=es"
        );
        assertThat(resolved).isEqualTo("http://localhost:5173/app?lang=es&error=login_failed");
    }

    @Test
    void rejectsAPreLoginTimeoutLongerThanTheSessionIdleTimeout() {
        assertThatThrownBy(() -> TenantSecurityConfiguration.requirePreLoginTimeoutWithinIdleTimeout(
                Duration.ofMinutes(5), Duration.ofMinutes(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dokene.security.session.pre-login-timeout")
                .hasMessageContaining("server.servlet.session.timeout");
    }

    @Test
    void acceptsAPreLoginTimeoutUpToTheSessionIdleTimeout() {
        assertThat(TenantSecurityConfiguration.requirePreLoginTimeoutWithinIdleTimeout(
                Duration.ofMinutes(5), Duration.ofMinutes(5))).isEqualTo(Duration.ofMinutes(5));
        assertThat(TenantSecurityConfiguration.requirePreLoginTimeoutWithinIdleTimeout(
                Duration.ofMinutes(5), Duration.ofMinutes(30))).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void acceptsAnyPreLoginTimeoutWhenSessionsNeverIdleOut() {
        // The servlet container reads a zero or negative idle timeout as "never expire".
        assertThatCode(() -> TenantSecurityConfiguration.requirePreLoginTimeoutWithinIdleTimeout(
                Duration.ofMinutes(5), Duration.ZERO)).doesNotThrowAnyException();
        assertThatCode(() -> TenantSecurityConfiguration.requirePreLoginTimeoutWithinIdleTimeout(
                Duration.ofMinutes(5), Duration.ofSeconds(-1))).doesNotThrowAnyException();
    }

    @Test
    void aFailedLoginEndsItsLoginStartSessionAndExpiresTheConfiguredSessionCookie() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletResponse response = new MockHttpServletResponse();

        TenantSecurityConfiguration.createAuthenticationFailureHandler("/?error=login_failed", "__Host-SESSION")
                .onAuthenticationFailure(requestWith(session), response, new BadCredentialsException("failed"));

        assertThat(session.isInvalid()).isTrue();
        Cookie cookie = response.getCookie("__Host-SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isZero();
        assertThat(response.getCookie("JSESSIONID")).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/?error=login_failed");
    }

    @Test
    void aFailedLoginNeverEndsASignedInSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(BffSessionLifetimeFilter.AUTHENTICATED_AT, Instant.parse("2026-11-10T08:00:00Z"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        TenantSecurityConfiguration.createAuthenticationFailureHandler("/?error=login_failed", "JSESSIONID")
                .onAuthenticationFailure(requestWith(session), response, new BadCredentialsException("failed"));

        assertThat(session.isInvalid()).isFalse();
        assertThat(response.getCookie("JSESSIONID")).isNull();
    }

    @Test
    void aFailedLoginEndsASessionWhoseSignedInMarkerIsNotAnInstant() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(BffSessionLifetimeFilter.AUTHENTICATED_AT, "not an instant");

        TenantSecurityConfiguration.createAuthenticationFailureHandler("/?error=login_failed", "JSESSIONID")
                .onAuthenticationFailure(requestWith(session), new MockHttpServletResponse(),
                        new BadCredentialsException("failed"));

        assertThat(session.isInvalid()).isTrue();
    }

    private static MockHttpServletRequest requestWith(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/dokene");
        request.setSession(session);
        return request;
    }
}
