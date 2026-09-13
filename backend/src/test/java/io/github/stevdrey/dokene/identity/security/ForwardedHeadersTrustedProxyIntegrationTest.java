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
        registry.add("spring.security.oauth2.client.registration.dokene.provider", () -> "dokene");
        registry.add("spring.security.oauth2.client.registration.dokene.client-id", () -> "test-client");
        registry.add("spring.security.oauth2.client.registration.dokene.client-secret", () -> "test-secret");
        registry.add("spring.security.oauth2.client.registration.dokene.authorization-grant-type",
                () -> "authorization_code");
        registry.add("spring.security.oauth2.client.registration.dokene.redirect-uri",
                () -> "{baseUrl}/login/oauth2/code/{registrationId}");
        registry.add("spring.security.oauth2.client.provider.dokene.authorization-uri",
                () -> "http://idp.example.test/authorize");
        registry.add("spring.security.oauth2.client.provider.dokene.token-uri",
                () -> "http://idp.example.test/token");
    }

    @LocalServerPort
    private int port;

    @Test
    void trustedProxyForwardedHeadersReconstructHttpsAuthorizationRedirectUri() throws Exception {
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
        assertThat(location).contains("redirect_uri=https://auth.dokene.example/login/oauth2/code/dokene");
    }
}
