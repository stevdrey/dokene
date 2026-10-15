// ABOUTME: Gives the message routes the closed problem+json shape for tenant-selection rejections (ADR 0023 §4.4).
// ABOUTME: The filter keeps the status (400 or 403) and the audit; this only owns the route list and the body.
package io.github.stevdrey.dokene.messaging.api;

import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessagingProblem;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.security.ProblemJsonTenantSelectionResponder;
import io.github.stevdrey.dokene.tenant.security.TenantSelectionRejection;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.stereotype.Component;

@Component
class MessageRoutesTenantSelectionResponder extends ProblemJsonTenantSelectionResponder {

    MessageRoutesTenantSelectionResponder() {
        super(new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern("/api/messages"),
                PathPatternRequestMatcher.pathPattern("/api/messages/**"),
                PathPatternRequestMatcher.pathPattern("/api/customers/*/messages")));
    }

    @Override
    protected MessagingProblem problem(TenantSelectionRejection rejection) {
        return MessagingProblem.of(switch (rejection) {
            case INVALID_SELECTOR -> MessagingErrorCode.INVALID_INPUT;
            case ACCESS_DENIED -> MessagingErrorCode.FORBIDDEN;
        });
    }
}
