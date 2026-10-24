// ABOUTME: Gives the message routes the closed problem+json shape for tenant-selection rejections (ADR 0023 §4.4).
// ABOUTME: The filter keeps the status (400 or 403) and the audit; this only owns the route list.
package io.github.stevdrey.dokene.messaging.api;

import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.stereotype.Component;

@Component
class MessageRoutesTenantSelectionResponder extends MessagingProblemTenantSelectionResponder {

    MessageRoutesTenantSelectionResponder() {
        super(new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern("/api/messages/**"),
                PathPatternRequestMatcher.pathPattern("/api/messaging/**"),
                PathPatternRequestMatcher.pathPattern("/api/customers/*/messages")));
    }
}
