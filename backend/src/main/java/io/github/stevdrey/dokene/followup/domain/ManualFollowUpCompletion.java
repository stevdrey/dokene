package io.github.stevdrey.dokene.followup.domain;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record ManualFollowUpCompletion(UUID id, TenantId tenantId, CustomerId customerId,
        LocalDate completedOn, long policyVersion, Instant occurredAt, IdentityId actorId,
        TenantMembershipId membershipId, String notes) {
    public ManualFollowUpCompletion {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(completedOn);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(membershipId);
        if (policyVersion < 1) throw new IllegalArgumentException("Policy version must be positive");
        if (notes != null && notes.length() > 500) {
            throw new IllegalArgumentException("Notes cannot exceed 500 characters");
        }
    }

    public ManualFollowUpCompletion(UUID id, TenantId tenantId, CustomerId customerId,
            LocalDate completedOn, long policyVersion, Instant occurredAt, IdentityId actorId,
            TenantMembershipId membershipId) {
        this(id, tenantId, customerId, completedOn, policyVersion, occurredAt, actorId, membershipId, null);
    }
}
