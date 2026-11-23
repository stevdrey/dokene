// ABOUTME: Runs every HttpsOriginHstsIntegrationTest case under the "framework" forwarded header strategy (spec 0019
// ABOUTME: AC-6, issue 318), where a forwarded scheme changes the request but must not switch HSTS off.
package io.github.stevdrey.dokene.identity.security;

import org.springframework.boot.web.server.autoconfigure.ServerProperties.ForwardHeadersStrategy;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "server.forward-headers-strategy=framework")
class HttpsOriginHstsFrameworkIntegrationTest extends HttpsOriginHstsIntegrationTest {

    @Override
    ForwardHeadersStrategy strategy() {
        return ForwardHeadersStrategy.FRAMEWORK;
    }
}
