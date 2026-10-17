// ABOUTME: The one mapping from a tenant-selection rejection to the closed messaging problem (ADR 0023 §4.4).
// ABOUTME: Route owners that answer with MessagingProblem (messages, integrations) extend it with their own routes.
package io.github.stevdrey.dokene.messaging.api;

import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessagingProblem;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.security.ProblemJsonTenantSelectionResponder;
import io.github.stevdrey.dokene.tenant.security.TenantSelectionRejection;
import org.springframework.security.web.util.matcher.RequestMatcher;

public abstract class MessagingProblemTenantSelectionResponder extends ProblemJsonTenantSelectionResponder {

    protected MessagingProblemTenantSelectionResponder(RequestMatcher ownedRoutes) {
        super(ownedRoutes, MessagingProblemTenantSelectionResponder::problemOf, MessagingProblem::status);
    }

    private static MessagingProblem problemOf(TenantSelectionRejection rejection) {
        return MessagingProblem.of(switch (rejection) {
            case INVALID_SELECTOR -> MessagingErrorCode.INVALID_INPUT;
            case ACCESS_DENIED -> MessagingErrorCode.FORBIDDEN;
        });
    }
}
