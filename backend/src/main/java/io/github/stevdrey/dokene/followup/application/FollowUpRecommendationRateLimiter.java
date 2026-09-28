package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;

/**
 * Rate limiter port protecting AI recommendation orchestration and provider endpoints.
 */
public interface FollowUpRecommendationRateLimiter {

    /**
     * Acquires a permit for the specified tenant and actor identity.
     *
     * @param tenantId current tenant identifier
     * @param identityId authenticated caller identity identifier
     * @throws RecommendationRateLimitExceededException if rate limit is exceeded
     */
    void acquire(TenantId tenantId, IdentityId identityId);
}
