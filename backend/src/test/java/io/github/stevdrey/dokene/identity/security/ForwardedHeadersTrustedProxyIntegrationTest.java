// ABOUTME: Under the framework strategy the backend believes forwarded headers for the request itself, but never for
// ABOUTME: the OAuth redirect: the callback stays on the configured public origin (spec 0019 AC-1, issue 318).
package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.forward-headers-strategy=framework"
})
class ForwardedHeadersTrustedProxyIntegrationTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        OidcProviderStub.register(registry);
    }

    @LocalServerPort
    private int port;

    /** covers: AC-1. Before spec 0019 these headers moved the callback to https://auth.dokene.example. */
    @Test
    void forwardedHeadersDoNotMoveTheAuthorizationRedirectUriOffTheConfiguredOrigin() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/oauth2/authorization/dokene"))
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "auth.dokene.example")
                .header("X-Forwarded-Port", "443")
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).doesNotContain("auth.dokene.example");
        assertThat(OidcProviderStub.parameters(URI.create(location).getRawQuery()).get("redirect_uri"))
                .isEqualTo("http://127.0.0.1:" + port + "/login/oauth2/code/dokene");
    }
}
