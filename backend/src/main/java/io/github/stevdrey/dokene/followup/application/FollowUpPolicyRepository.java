package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.FollowUpDismissal;
import io.github.stevdrey.dokene.followup.domain.ManualFollowUpCompletion;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface FollowUpPolicyRepository {
    TenantFollowUpPolicy tenantPolicy(TenantId tenantId);
    CustomerFollowUpPolicy customerPolicy(TenantId tenantId, CustomerId customerId);
    TenantFollowUpPolicy updateTenantPolicy(TenantFollowUpPolicy policy, long expectedVersion);
    CustomerFollowUpPolicy updateCustomerPolicy(TenantId tenantId, CustomerId customerId, Integer cadenceDays,
                                                LocalDate explicitNextDate, long expectedVersion);
    FollowUpQueuePage findDueQueue(TenantId tenantId, FollowUpQueueQuery query, LocalDate today,
            java.time.ZoneId zoneId, Instant evaluatedAt);
    Optional<ManualFollowUpCompletion> findCompletion(TenantId tenantId, String idempotencyKey);
    Optional<FollowUpDismissal> findDismissal(TenantId tenantId, String idempotencyKey);
    ManualFollowUpResult recordManualFollowUp(TenantId tenantId, CustomerId customerId, LocalDate date,
            long expectedVersion, String idempotencyKey, Instant occurredAt, IdentityId actorId,
            TenantMembershipId membershipId, String notes);
    default ManualFollowUpResult recordManualFollowUp(TenantId tenantId, CustomerId customerId, LocalDate date,
            long expectedVersion, String idempotencyKey, Instant occurredAt, IdentityId actorId,
            TenantMembershipId membershipId) {
        return recordManualFollowUp(tenantId, customerId, date, expectedVersion, idempotencyKey,
                occurredAt, actorId, membershipId, null);
    }
    FollowUpDismissalResult recordDismissal(TenantId tenantId, CustomerId customerId, LocalDate date,
            long expectedVersion, String idempotencyKey, Instant occurredAt, IdentityId actorId,
            TenantMembershipId membershipId, String notes);
    CustomerFollowUpPolicy snooze(TenantId tenantId, CustomerId customerId, LocalDate until, long expectedVersion);
}
