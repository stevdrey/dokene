// ABOUTME: The ten outbound message states fixed by ADR 0023 §2.
// ABOUTME: Knows which states are terminal, which are open, and which still accept operator commands.
package io.github.stevdrey.dokene.messaging.domain;

public enum MessageStatus {
    PENDING_APPROVAL, APPROVED, QUEUED, SENDING, SENT, DELIVERED, READ, REJECTED, CANCELLED, FAILED;

    /** No transition leaves a terminal state. */
    public boolean terminal() {
        return this == READ || this == REJECTED || this == CANCELLED || this == FAILED;
    }

    /** Open states block a second submit for the same customer (ADR 0023 §2, SENT and DELIVERED included). */
    public boolean open() {
        return !terminal();
    }
}
