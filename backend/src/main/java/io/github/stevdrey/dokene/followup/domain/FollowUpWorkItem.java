// ABOUTME: A read-only view of one follow-up work ledger row (issue 458, ADR 0037) for diagnostics and tests. It holds
// ABOUTME: ids, states and instants only; the claim token stays inside the ledger.
package io.github.stevdrey.dokene.followup.domain;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record FollowUpWorkItem(UUID candidateId, TenantId tenantId, CustomerId customerId, LocalDate dueDate,
        FollowUpWorkState state, int attempts, Instant availableAt, Optional<FollowUpWorkerId> claimedBy,
        Optional<Instant> leaseExpiresAt, Optional<FollowUpWorkFailure> lastFailure, Optional<UUID> collapsedInto,
        Instant enqueuedAt, Optional<Instant> finishedAt) {

    public FollowUpWorkItem {
        Objects.requireNonNull(candidateId);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(dueDate);
        Objects.requireNonNull(state);
        Objects.requireNonNull(availableAt);
        Objects.requireNonNull(claimedBy);
        Objects.requireNonNull(leaseExpiresAt);
        Objects.requireNonNull(lastFailure);
        Objects.requireNonNull(collapsedInto);
        Objects.requireNonNull(enqueuedAt);
        Objects.requireNonNull(finishedAt);
    }
}
