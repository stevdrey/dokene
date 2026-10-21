// ABOUTME: Durable webhook inbox port (issue 139, ADR 0030): typed rows in provider_webhook_events, never raw JSON.
// ABOUTME: Tenant-scoped methods run under the row's tenant; the two cross-tenant reads return ids and counts only.
package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.integration.application.VerifiedWebhookEvent.Provider;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface WebhookInboxRepository {

    /** Inserts a PENDING row unless the (provider, providerEventId) pair exists in any tenant; true when inserted. */
    boolean insertPending(InboxEvent event);

    /** The PENDING row, locked for this transaction; empty if processed meanwhile or held by another worker. */
    Optional<InboxEvent> lockPending(TenantId tenantId, UUID id);

    void markProcessed(TenantId tenantId, UUID id, InboxState state, Instant processedAt);

    /** Ends the park window: UNMATCHED, with the provider message id cleared; the event id stays for dedup. */
    void markUnmatched(TenantId tenantId, UUID id, Instant processedAt);

    /**
     * Gives up on a PENDING row received at or before {@code cutoff}: ABANDONED, with the provider message id cleared.
     * True when the row was abandoned; false leaves it untouched.
     */
    boolean abandonIfReceivedBefore(TenantId tenantId, UUID id, Instant cutoff, Instant processedAt);

    /** Keeps the row PENDING and moves its next attempt out by {@code base} doubled per attempt, up to {@code cap}. */
    void reschedule(TenantId tenantId, UUID id, Instant now, Duration base, Duration cap);

    /** Ids and tenants of PENDING rows due by {@code dueAt}, through the security-definer function. */
    List<DueEvent> findDue(Instant dueAt, int limit);

    /** Deletes up to {@code limit} terminal rows processed before {@code processedBefore}; returns how many. */
    int purgeProcessedBefore(Instant processedBefore, int limit);

    /** Runs {@code run} only if no other instance holds the sweeper lock; empty when it does. */
    <T> Optional<T> runAsSoleSweeper(Supplier<T> run);

    enum InboxState { PENDING, APPLIED, IGNORED, UNMATCHED, ABANDONED }

    record DueEvent(TenantId tenantId, UUID id) {
        public DueEvent {
            Objects.requireNonNull(tenantId, "Tenant id is required");
            Objects.requireNonNull(id, "Event id is required");
        }
    }

    record InboxEvent(UUID id, TenantId tenantId, Provider provider, String providerEventId,
            String providerMessageId, DeliveryStatus status, Instant occurredAt, Optional<FailureCategory> failure,
            int attempts, Instant receivedAt) {
        public InboxEvent {
            Objects.requireNonNull(id, "Id is required");
            Objects.requireNonNull(tenantId, "Tenant id is required");
            Objects.requireNonNull(provider, "Provider is required");
            Objects.requireNonNull(providerEventId, "Provider event id is required");
            Objects.requireNonNull(providerMessageId, "Provider message id is required");
            Objects.requireNonNull(status, "Status is required");
            Objects.requireNonNull(occurredAt, "Report time is required");
            Objects.requireNonNull(failure, "Failure option is required");
            Objects.requireNonNull(receivedAt, "Receipt time is required");
        }

        @Override
        public String toString() {
            return "InboxEvent[id=" + id + ", tenantId=" + tenantId.value() + ", status=" + status + "]";
        }
    }
}
