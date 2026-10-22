// ABOUTME: Drives the /webhooks/** chain over real HTTP (issue 266, P3-CSRF-05): no path under it sets a cookie or
// ABOUTME: starts a session, denied or not, while the BFF chain keeps refusing an unsafe request without a CSRF token.
package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.integration.testsupport.WebhookSigning;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dokene.ai.provider=fake", "dokene.integration.whatsapp.webhook.inbox.enabled=false"})
@Import(WebhookChainStatelessIntegrationTest.SessionCounter.class)
class WebhookChainStatelessIntegrationTest {
    /** Synthetic values, never a real Meta app secret or verify token. */
    private static final char[] APP_SECRET = "SYNTHETIC-APP-SECRET-NOT-REAL-0266".toCharArray();
    private static final String VERIFY_TOKEN = "SYNTHETIC-VERIFY-TOKEN-0266";
    private static final byte[] BODY = "{\"object\":\"whatsapp_business_account\",\"entry\":[]}"
            .getBytes(StandardCharsets.UTF_8);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("dokene.integration.secrets.source", () -> "local");
        registry.add("dokene.integration.secrets.local.entries[0].scope", () -> "deployment");
        registry.add("dokene.integration.secrets.local.entries[0].ref", () -> "webhook-app-secret");
        registry.add("dokene.integration.secrets.local.entries[0].purpose", () -> "APP_SECRET");
        registry.add("dokene.integration.secrets.local.entries[0].value", () -> new String(APP_SECRET));
        registry.add("dokene.integration.secrets.local.entries[1].scope", () -> "deployment");
        registry.add("dokene.integration.secrets.local.entries[1].ref", () -> "webhook-verify-token");
        registry.add("dokene.integration.secrets.local.entries[1].purpose", () -> "WEBHOOK_VERIFY_TOKEN");
        registry.add("dokene.integration.secrets.local.entries[1].value", () -> VERIFY_TOKEN);
        registry.add("dokene.integration.whatsapp.app-secret-ref", () -> "webhook-app-secret");
        registry.add("dokene.integration.whatsapp.verify-token-ref", () -> "webhook-verify-token");
    }

    @LocalServerPort int port;
    @Autowired AtomicInteger sessionsCreated;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetCounter() {
        sessionsCreated.set(0);
    }

    /** covers: issue 266 and P3-CSRF-05, a path under /webhooks/** that is not the callback. */
    @Test
    void aNonCallbackWebhookPathIsDeniedWithoutACookieOrASession() throws Exception {
        assertDeniedWithoutSession(post("/webhooks/other", BODY, null));
        assertDeniedWithoutSession(post("/webhooks/other", BODY, WebhookSigning.sign(BODY, APP_SECRET)));
        assertDeniedWithoutSession(get("/webhooks/other"));
        assertDeniedWithoutSession(post("/webhooks/whatsapp/other", BODY, null));
        assertThat(sessionsCreated).hasValue(0);
    }

    @Test
    void theCallbackStillRefusesBadCredentialsWithoutACookieOrASession() throws Exception {
        assertDeniedWithoutSession(post("/webhooks/whatsapp", BODY, null));
        assertDeniedWithoutSession(post("/webhooks/whatsapp", BODY,
                WebhookSigning.sign(BODY, "wrong".toCharArray())));
        assertDeniedWithoutSession(get("/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=wrong"
                + "&hub.challenge=challenge-266"));

        HttpResponse<String> verified = get("/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=" + VERIFY_TOKEN
                + "&hub.challenge=challenge-266");
        assertThat(verified.statusCode()).isEqualTo(200);
        assertThat(verified.body()).isEqualTo("challenge-266");
        assertThat(verified.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(sessionsCreated).hasValue(0);
    }

    @Test
    void theBffChainStillRefusesAnUnsafeRequestWithoutACsrfToken() throws Exception {
        HttpResponse<String> refused = post("/api/messages", BODY, null);

        assertThat(refused.statusCode()).isIn(401, 403);
    }

    private void assertDeniedWithoutSession(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.uri().toString()).isEqualTo(403);
        assertThat(response.headers().allValues("Set-Cookie")).as(response.uri().toString()).isEmpty();
    }

    private HttpResponse<String> post(String path, byte[] body, String signature) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (signature != null) {
            request.header("X-Hub-Signature-256", signature);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    /** Counts every HttpSession the embedded server creates, whichever filter or dispatch asked for it. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SessionCounter {
        @Bean
        AtomicInteger sessionsCreated() {
            return new AtomicInteger();
        }

        @Bean
        HttpSessionListener sessionCreationCounter(AtomicInteger sessionsCreated) {
            return new HttpSessionListener() {
                @Override
                public void sessionCreated(HttpSessionEvent event) {
                    sessionsCreated.incrementAndGet();
                }
            };
        }
    }
}
