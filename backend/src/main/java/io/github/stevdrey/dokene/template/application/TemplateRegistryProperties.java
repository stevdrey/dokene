package io.github.stevdrey.dokene.template.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code approvalMaxAge}: how long provider approval evidence stays usable before an explicit refresh is needed. */
@ConfigurationProperties(prefix = "dokene.template")
public record TemplateRegistryProperties(Duration approvalMaxAge) {
    public static final Duration DEFAULT_APPROVAL_MAX_AGE = Duration.ofHours(24);

    public TemplateRegistryProperties {
        approvalMaxAge = approvalMaxAge == null ? DEFAULT_APPROVAL_MAX_AGE : approvalMaxAge;
        if (approvalMaxAge.isZero() || approvalMaxAge.isNegative()) {
            throw new IllegalArgumentException("Approval max age must be positive");
        }
    }
}
