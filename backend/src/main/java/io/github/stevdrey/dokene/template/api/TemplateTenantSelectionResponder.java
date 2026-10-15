// ABOUTME: Gives /api/templates/** the closed template problem+json shape for tenant-selection rejections.
// ABOUTME: The filter keeps the status (400 or 403) and the audit; this only owns the route list and the body.
package io.github.stevdrey.dokene.template.api;

import io.github.stevdrey.dokene.template.api.TemplateApiContract.TemplateProblem;
import io.github.stevdrey.dokene.template.application.TemplateErrorCode;
import io.github.stevdrey.dokene.tenant.security.ProblemJsonTenantSelectionResponder;
import io.github.stevdrey.dokene.tenant.security.TenantSelectionRejection;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;

@Component
class TemplateTenantSelectionResponder extends ProblemJsonTenantSelectionResponder {

    TemplateTenantSelectionResponder() {
        super(PathPatternRequestMatcher.pathPattern("/api/templates/**"));
    }

    @Override
    protected TemplateProblem problem(TenantSelectionRejection rejection) {
        return TemplateProblem.of(switch (rejection) {
            case INVALID_SELECTOR -> TemplateErrorCode.INVALID_INPUT;
            case ACCESS_DENIED -> TemplateErrorCode.FORBIDDEN;
        });
    }
}
