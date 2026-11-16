// ABOUTME: Sends forged Host and forwarded headers straight to the backend port (spec 0019 AC-1, AC-2, AC-6, issue
// ABOUTME: 318): the login callback, the logout return and HSTS follow the configured origin, here under "none".
package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties.ForwardHeadersStrategy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfiguredRedirectOriginIntegrationTest {

    /** Everything a direct caller can set to claim another origin. */
    private static final Map<String, String> FORGED = Map.of(
            "Host", "attacker.example",
            "Forwarded", "for=203.0.113.9;host=attacker.example;proto=https",
            "X-Forwarded-Host", "attacker.example",
            "X-Forwarded-Proto", "https",
            "X-Forwarded-Port", "8443",
            "X-Forwarded-Prefix", "/attacker");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        OidcProviderStub.register(registry);
    }

    @LocalServerPort
    private int port;

    @Value("${dokene.security.public-origin}")
    private String origin;

    @Value("${spring.security.oauth2.client.provider.dokene.issuer-uri}")
    private String issuer;

    @Autowired
    private ServerProperties serverProperties;

    @Autowired
    private ObjectMapper objectMapper;

    ForwardHeadersStrategy strategy() {
        return ForwardHeadersStrategy.NONE;
    }

    /** Guards the subclass: without this, both classes could silently run under the same strategy. */
    @Test
    void runsUnderTheStrategyItNames() {
        assertThat(serverProperties.getForwardHeadersStrategy()).isEqualTo(strategy());
    }

    /** covers: AC-1. */
    @Test
    void aForgedHostAndForwardedHeadersDoNotChangeTheLoginCallback() throws Exception {
        DirectBackend.Response response = DirectBackend.send(port, "GET", "/oauth2/authorization/dokene", FORGED);

        assertThat(response.status()).isEqualTo(302);
        String location = response.header("Location").orElseThrow();
        assertThat(location).doesNotContain("attacker");
        assertThat(queryParameter(location, "redirect_uri")).isEqualTo(origin + "/login/oauth2/code/dokene");
    }

    /** covers: AC-2. */
    @Test
    void aForgedHostAndForwardedHeadersDoNotChangeTheLogoutReturn() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        assertThat(browser.login().statusCode()).isEqualTo(302);
        String csrf = objectMapper.readTree(browser.get("/api/session").body()).path("csrfToken").asText();
        Map<String, String> headers = new LinkedHashMap<>(FORGED);
        headers.put("Cookie", "JSESSIONID=" + browser.sessionId().orElseThrow());
        headers.put("X-CSRF-TOKEN", csrf);

        DirectBackend.Response response = DirectBackend.send(port, "POST", "/logout?provider=true", headers);

        assertThat(response.status()).isEqualTo(302);
        String location = response.header("Location").orElseThrow();
        assertThat(location).startsWith(issuer + "/logout").doesNotContain("attacker");
        assertThat(queryParameter(location, "post_logout_redirect_uri")).isEqualTo(origin + "/");
    }

    /** covers: AC-6. The origin is http, so no chain may claim https, whatever scheme the caller forwards. */
    @Test
    void anHttpOriginSendsNoHstsOnEitherChainEvenWhenTheCallerClaimsHttps() throws Exception {
        assertThat(DirectBackend.send(port, "GET", "/api/session", FORGED).header("Strict-Transport-Security"))
                .isEmpty();
        assertThat(DirectBackend.send(port, "GET", "/webhooks/other", FORGED).header("Strict-Transport-Security"))
                .isEmpty();
    }

    private static String queryParameter(String location, String name) {
        return OidcProviderStub.parameters(URI.create(location).getRawQuery()).get(name);
    }
}
