package io.github.stevdrey.dokene.template.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budget of provider approval lookups per tenant within a sliding {@code window} (issue 321). Every refresh that reaches
 * the provider counts, including unavailable answers. Missing values take the defaults; non-positive values fail startup.
 */
@ConfigurationProperties(prefix = "dokene.template.refresh-limit")
public record TemplateRefreshLimitProperties(Integer tenantCalls, Duration window) {
    public static final int DEFAULT_TENANT_CALLS = 30;
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);

    public TemplateRefreshLimitProperties {
        tenantCalls = tenantCalls == null ? DEFAULT_TENANT_CALLS : tenantCalls;
        window = window == null ? DEFAULT_WINDOW : window;
        if (tenantCalls <= 0) {
            throw new IllegalArgumentException("Template refresh limit must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Template refresh window must be positive");
        }
    }

    public static TemplateRefreshLimitProperties defaults() {
        return new TemplateRefreshLimitProperties(null, null);
    }
}
