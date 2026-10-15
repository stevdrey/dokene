// ABOUTME: Gives /api/integrations/** the closed messaging problem+json shape for tenant-selection rejections.
// ABOUTME: The filter keeps the status (400 or 403) and the audit; this only owns the route list.
package io.github.stevdrey.dokene.integration.api;

import io.github.stevdrey.dokene.messaging.api.MessagingProblemTenantSelectionResponder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;

@Component
class IntegrationTenantSelectionResponder extends MessagingProblemTenantSelectionResponder {

    IntegrationTenantSelectionResponder() {
        super(PathPatternRequestMatcher.pathPattern("/api/integrations/**"));
    }
}
