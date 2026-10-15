// ABOUTME: Gives /api/integrations/** the closed messaging problem+json shape for tenant-selection rejections.
// ABOUTME: The filter keeps the status (400 or 403) and the audit; this only owns the route list and the body.
package io.github.stevdrey.dokene.integration.api;

import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessagingProblem;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.security.ProblemJsonTenantSelectionResponder;
import io.github.stevdrey.dokene.tenant.security.TenantSelectionRejection;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;

@Component
class IntegrationTenantSelectionResponder extends ProblemJsonTenantSelectionResponder {

    IntegrationTenantSelectionResponder() {
        super(PathPatternRequestMatcher.pathPattern("/api/integrations/**"));
    }

    @Override
    protected MessagingProblem problem(TenantSelectionRejection rejection) {
        return MessagingProblem.of(switch (rejection) {
            case INVALID_SELECTOR -> MessagingErrorCode.INVALID_INPUT;
            case ACCESS_DENIED -> MessagingErrorCode.FORBIDDEN;
        });
    }
}
