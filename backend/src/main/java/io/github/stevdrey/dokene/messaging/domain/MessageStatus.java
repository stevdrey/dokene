// ABOUTME: The ten outbound message states fixed by ADR 0023 §2.
// ABOUTME: Knows which states are terminal, which are open, and which still accept operator commands.
package io.github.stevdrey.dokene.messaging.domain;

public enum MessageStatus {
    PENDING_APPROVAL, APPROVED, QUEUED, SENDING, SENT, DELIVERED, READ, REJECTED, CANCELLED, FAILED;

    /** No transition leaves a terminal state. */
    public boolean terminal() {
        return this == READ || this == REJECTED || this == CANCELLED || this == FAILED;
    }

    /**
     * Open states block a second submit or approval for the same customer (ADR 0023 §2, amended for issue 198): every
     * state before the provider accepted the message. SENT and DELIVERED are not terminal, yet do not block, because
     * their remaining reports may never arrive. The V30 index predicate names exactly these states.
     */
    public boolean open() {
        return this == PENDING_APPROVAL || this == APPROVED || this == QUEUED || this == SENDING;
    }
}
