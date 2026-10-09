// ABOUTME: The single cancellation recorded for a message, with the state it was cancelled from.
// ABOUTME: Notes live here only; they never reach events, audit or logs.
package io.github.stevdrey.dokene.messaging.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MessageCancellation(UUID id, UUID tenantId, UUID messageId, MessageStatus statusFrom, String note,
        Instant cancelledAt, UUID cancelledByMembershipId, UUID cancelledByActorId) {
    public static final int MAX_NOTE_LENGTH = 500;

    public MessageCancellation {
        Objects.requireNonNull(id, "Cancellation id is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(messageId, "Message id is required");
        Objects.requireNonNull(statusFrom, "Source status is required");
        Objects.requireNonNull(cancelledAt, "Cancellation time is required");
        Objects.requireNonNull(cancelledByMembershipId, "Membership id is required");
        Objects.requireNonNull(cancelledByActorId, "Actor id is required");
        if (statusFrom != MessageStatus.PENDING_APPROVAL && statusFrom != MessageStatus.APPROVED) {
            throw new IllegalArgumentException("Only pending or approved messages can be cancelled");
        }
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("Note exceeds " + MAX_NOTE_LENGTH + " characters");
        }
    }
}
