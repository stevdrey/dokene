// ABOUTME: With an https public origin, both security chains send HSTS on a plain http request to the backend port
// ABOUTME: (spec 0019 AC-6, issue 318): the header follows the configured origin, here under "none".
package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties.ForwardHeadersStrategy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** No client registration: the origin is still validated and still decides HSTS. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dokene.security.public-origin=https://dokene.example")
class HttpsOriginHstsIntegrationTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ServerProperties serverProperties;

    ForwardHeadersStrategy strategy() {
        return ForwardHeadersStrategy.NONE;
    }

    /** Guards the subclass: without this, both classes could silently run under the same strategy. */
    @Test
    void runsUnderTheStrategyItNames() {
        assertThat(serverProperties.getForwardHeadersStrategy()).isEqualTo(strategy());
    }

    /** covers: AC-6. The main chain, then the webhook chain; no includeSubDomains and no preload. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/session", "/webhooks/other"})
    void aPlainHttpRequestStillGetsHstsFromTheHttpsOrigin(String path) throws Exception {
        assertThat(DirectBackend.send(port, "GET", path, Map.of()).header("Strict-Transport-Security"))
                .contains("max-age=31536000");
        assertThat(DirectBackend.send(port, "GET", path, Map.of("X-Forwarded-Proto", "http"))
                .header("Strict-Transport-Security"))
                .contains("max-age=31536000");
    }
}
