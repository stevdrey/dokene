// ABOUTME: Drives the OIDC login over real HTTP and reads the server sessions behind it (issue 311): a login start
// ABOUTME: session is short lived, login restores the normal idle timeout, and a failed login cleans up at once.
package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import jakarta.servlet.http.HttpSession;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SessionTracker.class)
class PreLoginSessionIntegrationTest {

    private static final int PRE_LOGIN_IDLE_SECONDS = 300;
    private static final String WRONG_STATE_CALLBACK =
            "/login/oauth2/code/dokene?code=any-code&state=not-the-issued-state";

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
    void aLoginStartCreatesExactlyOneSessionWithThePreLoginIdleTimeout() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        int sessionsBefore = sessions.created();

        browser.startLogin();

        assertThat(sessions.created() - sessionsBefore).isEqualTo(1);
        HttpSession session = sessions.find(browser.sessionId().orElseThrow()).orElseThrow();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(PRE_LOGIN_IDLE_SECONDS);
        assertThat(session.getAttribute(BffSessionLifetimeFilter.PRE_LOGIN_STARTED_AT)).isInstanceOf(Instant.class);
        assertThat(session.getAttribute(BffSessionLifetimeFilter.AUTHENTICATED_AT)).isNull();
    }

    /** covers: AC-3. */
    @Test
    void aSuccessfulLoginRotatesTheIdAndRestoresTheNormalIdleTimeout() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        int sessionsBefore = sessions.created();
        String callbackUrl = browser.callbackUrlFrom(browser.startLogin());
        String preLoginId = browser.sessionId().orElseThrow();

        HttpResponse<String> callback = browser.get(callbackUrl);

        assertThat(callback.statusCode()).isEqualTo(302);
        String signedInId = browser.sessionId().orElseThrow();
        assertThat(signedInId).isNotEqualTo(preLoginId);
        assertThat(sessions.find(preLoginId)).isEmpty();
        assertThat(sessions.created() - sessionsBefore).isEqualTo(1);
        HttpSession session = sessions.find(signedInId).orElseThrow();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(normalIdleSeconds(session));
        assertThat(session.getMaxInactiveInterval()).isGreaterThan(PRE_LOGIN_IDLE_SECONDS);
        assertThat(session.getAttribute(BffSessionLifetimeFilter.AUTHENTICATED_AT)).isInstanceOf(Instant.class);
        assertThat(session.getAttribute(BffSessionLifetimeFilter.LAST_REVALIDATED_AT)).isInstanceOf(Instant.class);
        assertThat(session.getAttribute(BffSessionLifetimeFilter.PRE_LOGIN_STARTED_AT)).isNull();
        assertThat(browser.get("/api/session").statusCode()).isEqualTo(200);
    }

    /** covers: AC-4. */
    @Test
    void aFailedCallbackEndsTheLoginStartSessionAndExpiresItsCookie() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        browser.startLogin();
        String preLoginId = browser.sessionId().orElseThrow();

        HttpResponse<String> callback = browser.get(WRONG_STATE_CALLBACK);

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location").orElseThrow()).endsWith("/?error=login_failed");
        // The same clearing cookie as logout. This container writes the expiry as an Expires date in 1970.
        assertThat(callback.headers().allValues("Set-Cookie")).anySatisfy(cookie -> {
            assertThat(cookie).startsWith("JSESSIONID=;").containsIgnoringCase("Path=/");
            assertThat(cookie).satisfiesAnyOf(
                    expired -> assertThat(expired).containsIgnoringCase("Max-Age=0"),
                    expired -> assertThat(expired).containsIgnoringCase("Expires=Thu, 01 Jan 1970"));
        });
        assertThat(browser.sessionId()).isEmpty();
        assertThat(sessions.find(preLoginId)).isEmpty();
    }

    /** covers: AC-4. A cross-site link to the callback URL must not be able to sign a user out. */
    @Test
    void aFailedCallbackNeverEndsASignedInSession() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        browser.login();
        String signedInId = browser.sessionId().orElseThrow();

        HttpResponse<String> callback = browser.get(WRONG_STATE_CALLBACK);

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location").orElseThrow()).endsWith("/?error=login_failed");
        assertThat(callback.headers().allValues("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("JSESSIONID="));
        assertThat(browser.sessionId()).contains(signedInId);
        assertThat(browser.get("/api/session").statusCode()).isEqualTo(200);
    }

    /** covers: AC-5. */
    @Test
    void aSignedInUserWhoStartsLoginAgainKeepsTheNormalIdleTimeout() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        browser.login();
        String signedInId = browser.sessionId().orElseThrow();

        browser.startLogin();

        assertThat(browser.sessionId()).contains(signedInId);
        HttpSession session = sessions.find(signedInId).orElseThrow();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(normalIdleSeconds(session));
        assertThat(session.getAttribute(BffSessionLifetimeFilter.PRE_LOGIN_STARTED_AT)).isNull();
        assertThat(browser.get("/api/session").statusCode()).isEqualTo(200);
    }

    /** What the container applies to a new session: server.servlet.session.timeout in whole minutes. */
    private static int normalIdleSeconds(HttpSession session) {
        return session.getServletContext().getSessionTimeout() * 60;
    }
}
