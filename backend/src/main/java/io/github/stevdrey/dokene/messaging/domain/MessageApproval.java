// ABOUTME: The single approve or reject decision recorded for a message, with the fingerprint an approval binds.
// ABOUTME: Notes and fingerprints live here only; they never reach events, audit, logs or HTTP responses.
package io.github.stevdrey.dokene.messaging.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code fingerprint} is present exactly when the decision is {@code APPROVE} (ADR 0024 §2). It is empty only for a
 * rejection, or for an approval read back from before the column existed, which send policy step 5b never matches.
 */
public record MessageApproval(UUID id, UUID tenantId, UUID messageId, ApprovalDecision decision, String note,
        Optional<ApprovalFingerprint> fingerprint, Instant decidedAt, UUID decidedByMembershipId,
        UUID decidedByActorId) {
    public static final int MAX_NOTE_LENGTH = 500;

    public MessageApproval {
        Objects.requireNonNull(id, "Approval id is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(messageId, "Message id is required");
        Objects.requireNonNull(decision, "Decision is required");
        Objects.requireNonNull(fingerprint, "Fingerprint option is required");
        Objects.requireNonNull(decidedAt, "Decision time is required");
        Objects.requireNonNull(decidedByMembershipId, "Membership id is required");
        Objects.requireNonNull(decidedByActorId, "Actor id is required");
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("Note exceeds " + MAX_NOTE_LENGTH + " characters");
        }
        if (decision == ApprovalDecision.REJECT && fingerprint.isPresent()) {
            throw new IllegalArgumentException("A rejection binds no fingerprint");
        }
    }

    /** Redacted: the note and the fingerprint must never reach a log line or an exception message. */
    @Override
    public String toString() {
        return "MessageApproval[id=" + id + ", messageId=" + messageId + ", decision=" + decision
                + ", hasNote=" + (note != null) + ", fingerprint=" + (fingerprint.isPresent() ? "[redacted]" : "none")
                + ", decidedAt=" + decidedAt + "]";
    }

    /** A new approval always binds the fingerprint computed under the customer lock. */
    public static MessageApproval approve(UUID id, UUID tenantId, UUID messageId, String note,
            ApprovalFingerprint fingerprint, Instant decidedAt, UUID decidedByMembershipId, UUID decidedByActorId) {
        return new MessageApproval(id, tenantId, messageId, ApprovalDecision.APPROVE, note,
                Optional.of(Objects.requireNonNull(fingerprint, "Fingerprint is required")), decidedAt,
                decidedByMembershipId, decidedByActorId);
    }

    public static MessageApproval reject(UUID id, UUID tenantId, UUID messageId, String note, Instant decidedAt,
            UUID decidedByMembershipId, UUID decidedByActorId) {
        return new MessageApproval(id, tenantId, messageId, ApprovalDecision.REJECT, note, Optional.empty(),
                decidedAt, decidedByMembershipId, decidedByActorId);
    }
}
