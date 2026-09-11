package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import java.time.Instant;
import java.time.LocalDate;

public interface FollowUpPolicyRepository {
    TenantFollowUpPolicy tenantPolicy(TenantId tenantId);
    CustomerFollowUpPolicy customerPolicy(TenantId tenantId, CustomerId customerId);
    TenantFollowUpPolicy updateTenantPolicy(TenantFollowUpPolicy policy, long expectedVersion);
    CustomerFollowUpPolicy updateCustomerPolicy(TenantId tenantId, CustomerId customerId, Integer cadenceDays,
                                                LocalDate explicitNextDate, long expectedVersion);
    ManualFollowUpResult recordManualFollowUp(TenantId tenantId, CustomerId customerId, LocalDate date,
            long expectedVersion, String idempotencyKey, Instant occurredAt, IdentityId actorId,
            TenantMembershipId membershipId);
    CustomerFollowUpPolicy snooze(TenantId tenantId, CustomerId customerId, LocalDate until, long expectedVersion);
}
