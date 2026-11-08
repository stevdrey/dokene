package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.ratelimit.ActorCallBudget;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-tenant and per-member budget of provider ownership proofs (issue 321), so a member with
 * {@code INTEGRATION_MANAGE} cannot loop verifications, including ones the provider answers as unavailable, spend the
 * tenant's provider quota or lock other members out. Keyed by tenant and identity ids only: the candidate phone number
 * id never reaches this class. State is per instance.
 */
@Component
@EnableConfigurationProperties(OwnershipVerificationLimitProperties.class)
public class OwnershipVerificationLimiter {
    private final ActorCallBudget<TenantId, IdentityId> budget;

    public OwnershipVerificationLimiter(OwnershipVerificationLimitProperties properties, Clock clock) {
        this.budget = new ActorCallBudget<>(Objects.requireNonNull(properties, "Properties are required").limits(),
                clock);
    }

    /** Spends one proof, or throws {@link IntegrationRateLimitedException} when the tenant or member has none left. */
    public void acquire(TenantId tenantId, IdentityId identityId) {
        budget.admit(tenantId, identityId,
                refusal -> new IntegrationRateLimitedException(refusal.retryAfter(), refusal.report()));
    }
}
