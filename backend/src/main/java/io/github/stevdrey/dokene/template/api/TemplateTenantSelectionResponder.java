// ABOUTME: Gives /api/templates/** the closed template problem+json shape for tenant-selection rejections.
// ABOUTME: Keeps its own mapping because the template module may not depend on messaging (ADR 0024 §3).
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
        super(PathPatternRequestMatcher.pathPattern("/api/templates/**"), TemplateTenantSelectionResponder::problemOf,
                TemplateProblem::status);
    }

    private static TemplateProblem problemOf(TenantSelectionRejection rejection) {
        return TemplateProblem.of(switch (rejection) {
            case INVALID_SELECTOR -> TemplateErrorCode.INVALID_INPUT;
            case ACCESS_DENIED -> TemplateErrorCode.FORBIDDEN;
        });
    }
}
