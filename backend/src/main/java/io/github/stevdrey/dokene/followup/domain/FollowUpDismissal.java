package io.github.stevdrey.dokene.followup.domain;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record FollowUpDismissal(UUID id, TenantId tenantId, CustomerId customerId,
        LocalDate dismissedOn, long policyVersion, Instant occurredAt, IdentityId actorId,
        TenantMembershipId membershipId, String notes) {
    public FollowUpDismissal {
        Objects.requireNonNull(id, "ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(customerId, "Customer ID is required");
        Objects.requireNonNull(dismissedOn, "Dismissed on is required");
        Objects.requireNonNull(occurredAt, "Occurred at is required");
        Objects.requireNonNull(actorId, "Actor ID is required");
        Objects.requireNonNull(membershipId, "Membership ID is required");
        if (policyVersion < 1) throw new IllegalArgumentException("Policy version must be positive");
        if (notes != null && notes.length() > 500) {
            throw new IllegalArgumentException("Notes cannot exceed 500 characters");
        }
    }

    public FollowUpDismissal(UUID id, TenantId tenantId, CustomerId customerId,
            LocalDate dismissedOn, long policyVersion, Instant occurredAt, IdentityId actorId,
            TenantMembershipId membershipId) {
        this(id, tenantId, customerId, dismissedOn, policyVersion, occurredAt, actorId, membershipId, null);
    }
}
