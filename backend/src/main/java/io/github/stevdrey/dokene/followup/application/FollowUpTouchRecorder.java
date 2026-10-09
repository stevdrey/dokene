// ABOUTME: Port owned by followup that lets a sent message count as a completed follow up (ADR 0023 §4.6).
// ABOUTME: Runs inside the caller's transaction under the customer lock; writes no audit row of its own.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import java.time.Instant;

public interface FollowUpTouchRecorder {

    /**
     * Sets the customer's last outbound message date to the tenant local date of {@code sentAt}, clears any snooze
     * and explicit next date, and advances the policy version. MESSAGE_SENT is the audit record for this touch.
     */
    void recordOutboundMessage(CustomerId customerId, Instant sentAt);
}
