package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.ratelimit.CallBudget;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-tenant budget of provider ownership proofs (issue 321), so a member with {@code INTEGRATION_MANAGE} cannot loop
 * verifications, including ones the provider answers as unavailable, and spend the tenant's provider quota. Keyed by
 * tenant id only: the candidate phone number id never reaches this class. State is per instance.
 */
@Component
@EnableConfigurationProperties(OwnershipVerificationLimitProperties.class)
public class OwnershipVerificationLimiter {
    private final CallBudget<TenantId> budget;

    public OwnershipVerificationLimiter(OwnershipVerificationLimitProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "Properties are required");
        this.budget = new CallBudget<>(properties.tenantCalls(), properties.window(), clock);
    }

    /** Spends one proof of the tenant's budget, or throws {@link IntegrationRateLimitedException} when none is left. */
    public void acquire(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        budget.acquire(tenantId).ifPresent(delay -> {
            throw new IntegrationRateLimitedException(delay);
        });
    }
}
