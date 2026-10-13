// ABOUTME: Audit side mirror of the ten message states, so audit.domain never depends on messaging.
// ABOUTME: Names are intentionally identical to messaging.domain.MessageStatus.
package io.github.stevdrey.dokene.audit.domain;

public enum AuditMessageStatus {
    PENDING_APPROVAL, APPROVED, QUEUED, SENDING, SENT, DELIVERED, READ, REJECTED, CANCELLED, FAILED
}
