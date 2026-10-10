// ABOUTME: Maps message events onto the closed audit vocabulary; mirrors DurableAiOutcomeAuditAdapter.
// ABOUTME: Exhaustive switches so a new message event type or status fails the build, not the failure path.
package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditFailureCategory;
import io.github.stevdrey.dokene.audit.domain.AuditMessageStatus;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.messaging.application.MessageAuditPort;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.tenant.application.ProviderContext;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DurableMessageAuditAdapter implements MessageAuditPort {
    private final AuditRecorder recorder;

    public DurableMessageAuditAdapter(AuditRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "Audit recorder is required");
    }

    @Override
    public void submitted(MessageEvent event) {
        record(event, MessageEventType.SUBMITTED);
    }

    @Override
    public void approved(MessageEvent event) {
        record(event, MessageEventType.APPROVED);
    }

    @Override
    public void rejected(MessageEvent event) {
        record(event, MessageEventType.REJECTED);
    }

    @Override
    public void cancelled(MessageEvent event) {
        record(event, MessageEventType.CANCELLED);
    }

    @Override
    public void sendRequested(MessageEvent event) {
        record(event, MessageEventType.SEND_REQUESTED);
    }

    @Override
    public void sent(MessageEvent event) {
        record(event, MessageEventType.SENT);
    }

    @Override
    public void sendFailed(MessageEvent event) {
        record(event, MessageEventType.SEND_FAILED);
    }

    @Override
    public void sendOutcomeUnknown(MessageEvent event) {
        record(event, MessageEventType.SEND_OUTCOME_UNKNOWN);
    }

    @Override
    public void deliveryUpdated(ProviderContext context, MessageEvent event) {
        Objects.requireNonNull(context, "Provider context is required");
        requireType(event, MessageEventType.DELIVERY_UPDATED);
        if (!event.applied()) {
            throw new IllegalArgumentException("Only applied delivery reports are audited");
        }
        recorder.messageDeliveryUpdated(context.tenantId(), event.messageId(), metadata(event));
    }

    private void record(MessageEvent event, MessageEventType expected) {
        requireType(event, expected);
        recorder.messageTransition(event.messageId(), auditType(event.type()), metadata(event));
    }

    private static void requireType(MessageEvent event, MessageEventType expected) {
        Objects.requireNonNull(event, "Event is required");
        if (event.type() != expected) {
            throw new IllegalArgumentException("Event type does not match the audit method");
        }
    }

    private static AuditMetadata.MessageTransition metadata(MessageEvent event) {
        return new AuditMetadata.MessageTransition(
                event.statusFrom() == null ? null : status(event.statusFrom()), status(event.statusTo()),
                event.failureCategory() == null ? null : category(event.failureCategory()), event.attemptNumber());
    }

    static AuditEventType auditType(MessageEventType type) {
        return switch (type) {
            case SUBMITTED -> AuditEventType.MESSAGE_SUBMITTED;
            case APPROVED -> AuditEventType.MESSAGE_APPROVED;
            case REJECTED -> AuditEventType.MESSAGE_REJECTED;
            case CANCELLED -> AuditEventType.MESSAGE_CANCELLED;
            case SEND_REQUESTED -> AuditEventType.MESSAGE_SEND_REQUESTED;
            case SENT -> AuditEventType.MESSAGE_SENT;
            case SEND_FAILED -> AuditEventType.MESSAGE_SEND_FAILED;
            case SEND_OUTCOME_UNKNOWN -> AuditEventType.MESSAGE_SEND_OUTCOME_UNKNOWN;
            case DELIVERY_UPDATED -> AuditEventType.MESSAGE_DELIVERY_UPDATED;
            case SEND_ATTEMPT_STARTED ->
                    throw new IllegalArgumentException("Attempt start is recorded by the attempt row, not audit");
        };
    }

    static AuditMessageStatus status(MessageStatus status) {
        return switch (status) {
            case PENDING_APPROVAL -> AuditMessageStatus.PENDING_APPROVAL;
            case APPROVED -> AuditMessageStatus.APPROVED;
            case QUEUED -> AuditMessageStatus.QUEUED;
            case SENDING -> AuditMessageStatus.SENDING;
            case SENT -> AuditMessageStatus.SENT;
            case DELIVERED -> AuditMessageStatus.DELIVERED;
            case READ -> AuditMessageStatus.READ;
            case REJECTED -> AuditMessageStatus.REJECTED;
            case CANCELLED -> AuditMessageStatus.CANCELLED;
            case FAILED -> AuditMessageStatus.FAILED;
        };
    }

    static AuditFailureCategory category(FailureCategory category) {
        return switch (category) {
            case INVALID_RECIPIENT -> AuditFailureCategory.INVALID_RECIPIENT;
            case TEMPLATE_REJECTED -> AuditFailureCategory.TEMPLATE_REJECTED;
            case RATE_LIMITED -> AuditFailureCategory.RATE_LIMITED;
            case PROVIDER_UNAVAILABLE -> AuditFailureCategory.PROVIDER_UNAVAILABLE;
            case AUTHENTICATION -> AuditFailureCategory.AUTHENTICATION;
            case POLICY_VIOLATION -> AuditFailureCategory.POLICY_VIOLATION;
            case UNKNOWN -> AuditFailureCategory.UNKNOWN;
        };
    }
}
