package io.github.stevdrey.dokene.template.application;

import io.github.stevdrey.dokene.ratelimit.CallBudget;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-tenant budget of provider approval lookups (issue 321), so a member with {@code TEMPLATE_WRITE} cannot loop
 * refreshes and spend the tenant's provider quota. Keyed by tenant id only; state is per instance.
 */
@Component
@EnableConfigurationProperties(TemplateRefreshLimitProperties.class)
public class TemplateRefreshLimiter {
    private final CallBudget<TenantId> budget;

    public TemplateRefreshLimiter(TemplateRefreshLimitProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "Properties are required");
        this.budget = new CallBudget<>(properties.tenantCalls(), properties.window(), clock);
    }

    /** Spends one lookup of the tenant's budget, or throws {@link TemplateRateLimitedException} when none is left. */
    public void acquire(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        budget.acquire(tenantId).ifPresent(delay -> {
            throw new TemplateRateLimitedException(delay);
        });
    }
}
