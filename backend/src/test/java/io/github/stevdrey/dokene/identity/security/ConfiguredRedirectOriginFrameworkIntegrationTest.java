// ABOUTME: Runs every ConfiguredRedirectOriginIntegrationTest case under the "framework" forwarded header strategy
// ABOUTME: (spec 0019 AC-1, AC-2, AC-6, issue 318), where Spring believes X-Forwarded-* for the request itself.
package io.github.stevdrey.dokene.identity.security;

import org.springframework.boot.web.server.autoconfigure.ServerProperties.ForwardHeadersStrategy;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "server.forward-headers-strategy=framework")
class ConfiguredRedirectOriginFrameworkIntegrationTest extends ConfiguredRedirectOriginIntegrationTest {

    @Override
    ForwardHeadersStrategy strategy() {
        return ForwardHeadersStrategy.FRAMEWORK;
    }
}
