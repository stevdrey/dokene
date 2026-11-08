package io.github.stevdrey.dokene.template.application;

import io.github.stevdrey.dokene.ratelimit.ActorCallBudget;
import io.github.stevdrey.dokene.ratelimit.CallLimits;
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
        CallLimits limits = Objects.requireNonNull(properties, "Properties are required").limits();
        if (Math.min(limits.tenantCalls(), limits.actorCalls()) < TemplateProviderGateway.MAX_REQUESTS_PER_LOOKUP) {
            throw new IllegalArgumentException("Refresh limits must allow at least one full lookup");
        }
        this.budget = new ActorCallBudget<>(limits, clock);
    }

    /**
     * Admits one refresh and reserves the most provider requests a lookup can send, so overlapping refreshes never
     * spend more than the limit together, or throws {@link TemplateRateLimitedException} when the tenant or the member
     * has fewer units left. The ticket is settled with the requests the refresh made and refunds the rest.
     */
    public ActorCallBudget.Ticket admit(TenantId tenantId, IdentityId identityId) {
        return budget.admit(tenantId, identityId, TemplateProviderGateway.MAX_REQUESTS_PER_LOOKUP,
                refusal -> new TemplateRateLimitedException(refusal.retryAfter(), refusal.report()));
    }
}
