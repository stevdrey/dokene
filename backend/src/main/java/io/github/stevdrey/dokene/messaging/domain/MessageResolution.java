// ABOUTME: The single T12 resolution recorded for a message: who declared its unknown send outcome FAILED, and why.
// ABOUTME: Notes live here only; they never reach events, audit or logs.
package io.github.stevdrey.dokene.messaging.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MessageResolution(UUID id, UUID tenantId, UUID messageId, String note, Instant resolvedAt,
        UUID resolvedByMembershipId, UUID resolvedByActorId) {
    public static final int MAX_NOTE_LENGTH = 500;

    public MessageResolution {
        Objects.requireNonNull(id, "Resolution id is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(messageId, "Message id is required");
        Objects.requireNonNull(resolvedAt, "Resolution time is required");
        Objects.requireNonNull(resolvedByMembershipId, "Membership id is required");
        Objects.requireNonNull(resolvedByActorId, "Actor id is required");
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("Note exceeds " + MAX_NOTE_LENGTH + " characters");
        }
    }
}
