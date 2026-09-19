package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
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
        assertThat(configuration.getAllowedHeaders()).contains("Idempotency-Key");
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
}
