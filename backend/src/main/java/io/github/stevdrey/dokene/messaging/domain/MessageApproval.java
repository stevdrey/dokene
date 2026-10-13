// ABOUTME: The single approve or reject decision recorded for a message.
// ABOUTME: Notes live here only; they never reach events, audit or logs.
package io.github.stevdrey.dokene.messaging.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MessageApproval(UUID id, UUID tenantId, UUID messageId, ApprovalDecision decision, String note,
        Instant decidedAt, UUID decidedByMembershipId, UUID decidedByActorId) {
    public static final int MAX_NOTE_LENGTH = 500;

    public MessageApproval {
        Objects.requireNonNull(id, "Approval id is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(messageId, "Message id is required");
        Objects.requireNonNull(decision, "Decision is required");
        Objects.requireNonNull(decidedAt, "Decision time is required");
        Objects.requireNonNull(decidedByMembershipId, "Membership id is required");
        Objects.requireNonNull(decidedByActorId, "Actor id is required");
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("Note exceeds " + MAX_NOTE_LENGTH + " characters");
        }
    }
}
