// ABOUTME: Event types written to the per message event log.
// ABOUTME: The application layer maps each one onto an audit event type.
package io.github.stevdrey.dokene.messaging.domain;

public enum MessageEventType {
    SUBMITTED, APPROVED, REJECTED, CANCELLED, SEND_REQUESTED, SEND_ATTEMPT_STARTED,
    SENT, SEND_FAILED, SEND_OUTCOME_UNKNOWN, DELIVERY_UPDATED
}
