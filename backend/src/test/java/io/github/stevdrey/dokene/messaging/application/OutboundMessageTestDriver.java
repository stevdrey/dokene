// ABOUTME: Test scope driver for T6 to T11 (request send, start attempt, complete attempt) through the repository.
// ABOUTME: Stands in for the Phase 3 dispatcher so integration tests can walk a message from APPROVED to SENT.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpTouchRecorder;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.messaging.domain.Transition;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

public final class OutboundMessageTestDriver {
    public static final int MAX_SEND_ATTEMPTS = 3;

    private final OutboundMessageRepository messages;
    private final MessageAuditPort audit;
    private final FollowUpTouchRecorder touches;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OutboundMessageTestDriver(OutboundMessageRepository messages, MessageAuditPort audit,
            FollowUpTouchRecorder touches, TransactionTemplate transactions, Clock clock) {
        this.messages = Objects.requireNonNull(messages);
        this.audit = Objects.requireNonNull(audit);
        this.touches = Objects.requireNonNull(touches);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    /** T6: the caller's tenant context must be bound. */
    public OutboundMessage requestSend(TenantContext context, UUID messageId) {
        return transactions.execute(status -> {
            OutboundMessage current = locked(context, messageId);
            Transition transition = current.requestSend(context.membershipId().value(), clock.instant());
            OutboundMessage next = messages.update(current, transition.next());
            messages.appendEvent(transition.event());
            audit.sendRequested(transition.event());
            return next;
        });
    }

    /** T7: the attempt row commits before any provider call. */
    public OutboundMessage startAttempt(TenantContext context, UUID messageId) {
        return transactions.execute(status -> {
            OutboundMessage current = locked(context, messageId);
            Transition transition = current.startAttempt(clock.instant());
            OutboundMessage next = messages.update(current, transition.next());
            messages.appendEvent(transition.event());
            messages.insertAttempt(SendAttempt.started(next, context.membershipId().value(),
                    context.identityId().value(), clock.instant()));
            return next;
        });
    }

    /** T8 to T11; the cadence touch belongs to T8, as the dispatcher of feature 3 will do it. */
    public OutboundMessage completeAttempt(TenantContext context, UUID messageId, ProviderSendResult result) {
        return transactions.execute(status -> {
            OutboundMessage current = locked(context, messageId);
            Transition transition = current.completeAttempt(result, clock.instant(), MAX_SEND_ATTEMPTS);
            OutboundMessage next = messages.update(current, transition.next());
            messages.appendEvent(transition.event());
            SendAttempt open = messages.findAttempts(current.tenantId(), messageId).getLast();
            messages.updateAttempt(open.completed(result, clock.instant()));
            MessageEventType type = transition.event().type();
            switch (type) {
                case SENT -> {
                    touches.recordOutboundMessage(new CustomerId(next.customerId()), next.sentAt());
                    audit.sent(transition.event());
                }
                case SEND_FAILED -> audit.sendFailed(transition.event());
                case SEND_OUTCOME_UNKNOWN -> audit.sendOutcomeUnknown(transition.event());
                default -> throw new IllegalStateException("Unexpected attempt completion event " + type);
            }
            return next;
        });
    }

    /** APPROVED to SENT in three transactions with an accepted provider answer. */
    public OutboundMessage sendAccepted(TenantContext context, UUID messageId, String providerMessageId) {
        requestSend(context, messageId);
        startAttempt(context, messageId);
        return completeAttempt(context, messageId, new ProviderSendResult.Accepted(providerMessageId));
    }

    /** T6 to T8 ending in an unknown outcome (T11) that still carries the provider id, so a later report can land. */
    public OutboundMessage sendUnknownOutcome(TenantContext context, UUID messageId, String providerMessageId) {
        requestSend(context, messageId);
        startAttempt(context, messageId);
        return completeAttempt(context, messageId,
                new ProviderSendResult.OutcomeUnknown(java.util.Optional.of(providerMessageId)));
    }

    private OutboundMessage locked(TenantContext context, UUID messageId) {
        return messages.findByIdForUpdate(context.tenantId().value(), messageId)
                .orElseThrow(() -> new IllegalStateException("Message not found: " + messageId));
    }
}
