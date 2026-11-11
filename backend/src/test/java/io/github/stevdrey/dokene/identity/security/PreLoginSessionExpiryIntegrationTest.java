// ABOUTME: Runs the BFF chain with a one second pre login timeout (issue 311): an abandoned login start session ends
// ABOUTME: at that age even when its cookie keeps being used, and a late callback ends at the login failure redirect.
package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Its own class and context: with this timeout the success path tests elsewhere could expire mid flow. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dokene.security.session.pre-login-timeout=1s")
@Import(SessionTracker.class)
class PreLoginSessionExpiryIntegrationTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        OidcProviderStub.register(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SessionTracker sessions;

    /** covers: AC-2. */
    @Test
    void aCallbackAfterThePreLoginTimeoutEndsAtTheFailureRedirect() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        String callbackUrl = browser.callbackUrlFrom(browser.startLogin());
        String preLoginId = browser.sessionId().orElseThrow();
        assertThat(sessions.find(preLoginId).orElseThrow().getMaxInactiveInterval()).isEqualTo(1);

        Thread.sleep(Duration.ofMillis(1_500));
        HttpResponse<String> callback = browser.get(callbackUrl);

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location").orElseThrow()).endsWith("/?error=login_failed");
        assertThat(sessions.find(preLoginId)).isEmpty();
        assertThat(browser.get("/api/session").statusCode()).isEqualTo(401);
    }

    /** covers: AC-2. Using the cookie resets the idle timeout, so only the absolute age can end this session. */
    @Test
    void aLoginStartSessionEndsAtThePreLoginTimeoutHoweverOftenItsCookieIsUsed() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        String callbackUrl = browser.callbackUrlFrom(browser.startLogin());
        String preLoginId = browser.sessionId().orElseThrow();

        Instant end = Instant.now().plusSeconds(2);
        while (Instant.now().isBefore(end)) {
            assertThat(browser.get("/api/session").statusCode()).isEqualTo(401);
            Thread.sleep(Duration.ofMillis(300));
        }

        assertThat(sessions.find(preLoginId)).isEmpty();
        HttpResponse<String> callback = browser.get(callbackUrl);
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location").orElseThrow()).endsWith("/?error=login_failed");
    }
}
