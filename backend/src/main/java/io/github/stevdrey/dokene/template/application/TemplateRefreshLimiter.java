package io.github.stevdrey.dokene.template.application;

import io.github.stevdrey.dokene.ratelimit.ActorCallBudget;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-tenant and per-member budget of the provider requests approval refreshes make (issue 321), so a member with
 * {@code TEMPLATE_WRITE} cannot loop refreshes, spend the tenant's provider quota or lock other members out. Keyed by
 * tenant and identity ids only; state is per instance.
 */
@Component
@EnableConfigurationProperties(TemplateRefreshLimitProperties.class)
public class TemplateRefreshLimiter {
    private final ActorCallBudget<TenantId, IdentityId> budget;

    public TemplateRefreshLimiter(TemplateRefreshLimitProperties properties, Clock clock) {
        this.budget = new ActorCallBudget<>(Objects.requireNonNull(properties, "Properties are required").limits(),
                clock);
    }

    /**
     * Admits one refresh, to be settled with the provider requests it made, or throws
     * {@link TemplateRateLimitedException} when the tenant or the member has no unit left.
     */
    public ActorCallBudget.Ticket admit(TenantId tenantId, IdentityId identityId) {
        return budget.admit(tenantId, identityId,
                refusal -> new TemplateRateLimitedException(refusal.retryAfter(), refusal.report()));
    }
}
