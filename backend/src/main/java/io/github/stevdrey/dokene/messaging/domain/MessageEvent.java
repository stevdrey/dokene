// ABOUTME: One row of the append only per message event log.
// ABOUTME: The sequence number equals the message version after the row's transaction.
package io.github.stevdrey.dokene.messaging.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MessageEvent(UUID id, UUID tenantId, UUID messageId, long sequenceNumber, MessageEventType type,
        MessageStatus statusFrom, MessageStatus statusTo, boolean applied, MessageActorKind actorKind,
        UUID membershipId, FailureCategory failureCategory, Integer attemptNumber, Instant occurredAt) {
    public MessageEvent {
        Objects.requireNonNull(id, "Event id is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(messageId, "Message id is required");
        Objects.requireNonNull(type, "Event type is required");
        Objects.requireNonNull(statusTo, "Target status is required");
        Objects.requireNonNull(actorKind, "Actor kind is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("Sequence number starts at 1");
        }
        if ((type == MessageEventType.SUBMITTED) != (statusFrom == null)) {
            throw new IllegalArgumentException("Only SUBMITTED has no source status");
        }
        if (!applied && statusFrom != statusTo) {
            throw new IllegalArgumentException("A non applied event keeps the status");
        }
        if ((actorKind == MessageActorKind.MEMBER) != (membershipId != null)) {
            throw new IllegalArgumentException("Exactly MEMBER events carry a membership id");
        }
        if (attemptNumber != null && attemptNumber < 1) {
            throw new IllegalArgumentException("Attempt number starts at 1");
        }
    }
}
