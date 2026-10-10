// ABOUTME: The immutable outbound message aggregate; every ADR 0023 §2 transition is a pure method here.
// ABOUTME: Operator and system commands throw INVALID_TRANSITION when refused; delivery reports never throw.
package io.github.stevdrey.dokene.messaging.domain;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record OutboundMessage(UUID id, UUID tenantId, UUID customerId, UUID contactId, String recipientPhone,
        MessageChannel channel, MessageStatus status, boolean outcomeUnknown, SemanticAction action,
        SemanticTemplateIntent templateIntent, String locale, MessageOrigin origin, String body,
        long sourcePolicyVersion, UUID sendKey, int attemptCount, String providerMessageId,
        FailureCategory failureCategory, Instant sentAt, Instant deliveredAt, Instant readAt,
        UUID createdByMembershipId, UUID createdByActorId, Instant createdAt, Instant updatedAt, long version) {

    public static final int MAX_BODY_LENGTH = 1000;
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    public OutboundMessage {
        Objects.requireNonNull(id, "Message id is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(customerId, "Customer id is required");
        Objects.requireNonNull(contactId, "Contact id is required");
        Objects.requireNonNull(recipientPhone, "Recipient phone is required");
        Objects.requireNonNull(channel, "Channel is required");
        Objects.requireNonNull(status, "Status is required");
        Objects.requireNonNull(action, "Action is required");
        Objects.requireNonNull(templateIntent, "Template intent is required");
        Objects.requireNonNull(locale, "Locale is required");
        Objects.requireNonNull(origin, "Origin is required");
        Objects.requireNonNull(body, "Body is required");
        Objects.requireNonNull(sendKey, "Send key is required");
        Objects.requireNonNull(createdByMembershipId, "Creating membership id is required");
        Objects.requireNonNull(createdByActorId, "Creating actor id is required");
        Objects.requireNonNull(createdAt, "Creation time is required");
        Objects.requireNonNull(updatedAt, "Update time is required");
        if (!E164.matcher(recipientPhone).matches()) {
            throw new IllegalArgumentException("Recipient phone must be E.164");
        }
        if (body.isBlank() || body.codePointCount(0, body.length()) > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("Body must be 1 to " + MAX_BODY_LENGTH + " characters");
        }
        if (!MessagingLocales.supported(locale)) {
            throw new IllegalArgumentException("Unsupported locale");
        }
        if (sourcePolicyVersion < 0 || attemptCount < 0 || version < 0) {
            throw new IllegalArgumentException("Counters cannot be negative");
        }
        if (outcomeUnknown && status != MessageStatus.SENDING) {
            throw new IllegalArgumentException("Outcome can be unknown only while SENDING");
        }
        if (failureCategory != null && status != MessageStatus.FAILED) {
            throw new IllegalArgumentException("Failure category is set only on FAILED messages");
        }
        if (providerMessageId != null && (providerMessageId.isBlank() || providerMessageId.length() > 128)) {
            throw new IllegalArgumentException("Provider message id is malformed");
        }
    }

    /** Everything an operator fixes at submit time; the aggregate adds state, counters and timestamps. */
    public record Submission(UUID id, UUID tenantId, UUID customerId, UUID contactId, String recipientPhone,
            SemanticAction action, SemanticTemplateIntent templateIntent, String locale, MessageOrigin origin,
            String body, long sourcePolicyVersion, UUID sendKey, UUID createdByMembershipId,
            UUID createdByActorId) {
    }

    /** T1: a message is born in PENDING_APPROVAL at version 1 with its first event. */
    public static Transition submit(Submission submission, Instant now) {
        Objects.requireNonNull(submission, "Submission is required");
        Objects.requireNonNull(now, "Time is required");
        var message = new OutboundMessage(submission.id(), submission.tenantId(), submission.customerId(),
                submission.contactId(), submission.recipientPhone(), MessageChannel.WHATSAPP,
                MessageStatus.PENDING_APPROVAL, false, submission.action(), submission.templateIntent(),
                submission.locale(), submission.origin(), submission.body(), submission.sourcePolicyVersion(),
                submission.sendKey(), 0, null, null, null, null, null, submission.createdByMembershipId(),
                submission.createdByActorId(), now, now, 1);
        return new Transition(message, new MessageEvent(UUID.randomUUID(), message.tenantId(), message.id(), 1,
                MessageEventType.SUBMITTED, null, MessageStatus.PENDING_APPROVAL, true, MessageActorKind.MEMBER,
                submission.createdByMembershipId(), null, null, now));
    }

    /** T2. */
    public Transition approve(UUID byMembershipId, Instant now) {
        requireState(MessageStatus.PENDING_APPROVAL);
        return member(MessageStatus.APPROVED, MessageEventType.APPROVED, byMembershipId, now, null);
    }

    /** T3. */
    public Transition reject(UUID byMembershipId, Instant now) {
        requireState(MessageStatus.PENDING_APPROVAL);
        return member(MessageStatus.REJECTED, MessageEventType.REJECTED, byMembershipId, now, null);
    }

    /** T4 from PENDING_APPROVAL, T5 from APPROVED. */
    public Transition cancel(UUID byMembershipId, Instant now) {
        if (status != MessageStatus.PENDING_APPROVAL && status != MessageStatus.APPROVED) {
            throw new MessagingRefusedException(MessagingErrorCode.INVALID_TRANSITION);
        }
        return member(MessageStatus.CANCELLED, MessageEventType.CANCELLED, byMembershipId, now, null);
    }

    /** T6. */
    public Transition requestSend(UUID byMembershipId, Instant now) {
        requireState(MessageStatus.APPROVED);
        return member(MessageStatus.QUEUED, MessageEventType.SEND_REQUESTED, byMembershipId, now, null);
    }

    /** T7: the attempt counter advances here; the caller commits the attempt row before any provider call. */
    public Transition startAttempt(Instant now) {
        requireState(MessageStatus.QUEUED);
        int attempt = Math.incrementExact(attemptCount);
        var next = copy(MessageStatus.SENDING, false, attempt, providerMessageId, null, sentAt, deliveredAt,
                readAt, now);
        return new Transition(next, event(next, MessageEventType.SEND_ATTEMPT_STARTED, MessageActorKind.SYSTEM,
                null, null, attempt, now));
    }

    /** T8 to T11; T10 lands on FAILED instead of APPROVED once the attempt count reaches the maximum. */
    public Transition completeAttempt(ProviderSendResult result, Instant now, int maxSendAttempts) {
        Objects.requireNonNull(result, "Provider result is required");
        if (maxSendAttempts < 1) {
            throw new IllegalArgumentException("Maximum send attempts must be at least 1");
        }
        if (status != MessageStatus.SENDING || outcomeUnknown) {
            throw new MessagingRefusedException(MessagingErrorCode.INVALID_TRANSITION);
        }
        return switch (result) {
            case ProviderSendResult.Accepted accepted -> {
                var next = copy(MessageStatus.SENT, false, attemptCount, accepted.providerMessageId(), null, now,
                        deliveredAt, readAt, now);
                yield new Transition(next, event(next, MessageEventType.SENT, MessageActorKind.SYSTEM, null, null,
                        attemptCount, now));
            }
            case ProviderSendResult.RejectedPermanently rejected -> failed(rejected.category(), now);
            case ProviderSendResult.FailedTransiently failed -> {
                if (attemptCount >= maxSendAttempts) {
                    yield failed(failed.category(), now);
                }
                var next = copy(MessageStatus.APPROVED, false, attemptCount, providerMessageId, null, sentAt,
                        deliveredAt, readAt, now);
                yield new Transition(next, event(next, MessageEventType.SEND_FAILED, MessageActorKind.SYSTEM, null,
                        failed.category(), attemptCount, now));
            }
            case ProviderSendResult.OutcomeUnknown unknown -> {
                var next = copy(MessageStatus.SENDING, true, attemptCount,
                        unknown.providerMessageId().orElse(providerMessageId), null, sentAt, deliveredAt, readAt, now);
                yield new Transition(next, event(next, MessageEventType.SEND_OUTCOME_UNKNOWN, MessageActorKind.SYSTEM,
                        null, null, attemptCount, now));
            }
        };
    }

    /** T12: a human may resolve an unknown outcome as FAILED only. */
    public Transition resolveFailed(UUID byMembershipId, Instant now) {
        Objects.requireNonNull(byMembershipId, "Membership id is required");
        if (status != MessageStatus.SENDING || !outcomeUnknown) {
            throw new MessagingRefusedException(MessagingErrorCode.INVALID_TRANSITION);
        }
        var next = copy(MessageStatus.FAILED, false, attemptCount, providerMessageId, FailureCategory.UNKNOWN, sentAt,
                deliveredAt, readAt, now);
        return new Transition(next, event(next, MessageEventType.SEND_FAILED, MessageActorKind.MEMBER, byMembershipId,
                FailureCategory.UNKNOWN, attemptCount, now));
    }

    /** T13 to T16 per the spec 0001 delivery report table; anything else is recorded and ignored. */
    public DeliveryOutcome applyDeliveryReport(DeliveryStatusReport report, Instant now) {
        Objects.requireNonNull(report, "Report is required");
        Objects.requireNonNull(now, "Time is required");
        Instant at = boundReportTime(report.occurredAt(), now);
        OutboundMessage next = switch (status) {
            case SENDING -> !outcomeUnknown ? null : switch (report.status()) {
                case SENT -> copy(MessageStatus.SENT, false, attemptCount, providerMessageId, null, at, deliveredAt,
                        readAt, now);
                case DELIVERED -> copy(MessageStatus.DELIVERED, false, attemptCount, providerMessageId, null,
                        sentAt == null ? at : sentAt, at, readAt, now);
                case READ -> copy(MessageStatus.READ, false, attemptCount, providerMessageId, null,
                        sentAt == null ? at : sentAt, deliveredAt, at, now);
                case FAILED -> copy(MessageStatus.FAILED, false, attemptCount, providerMessageId,
                        report.failureOrUnknown(), sentAt, deliveredAt, readAt, now);
            };
            case SENT -> switch (report.status()) {
                case DELIVERED -> copy(MessageStatus.DELIVERED, false, attemptCount, providerMessageId, null, sentAt,
                        at, readAt, now);
                case READ -> copy(MessageStatus.READ, false, attemptCount, providerMessageId, null, sentAt,
                        deliveredAt, at, now);
                case FAILED -> copy(MessageStatus.FAILED, false, attemptCount, providerMessageId,
                        report.failureOrUnknown(), sentAt, deliveredAt, readAt, now);
                case SENT -> null;
            };
            case DELIVERED -> report.status() == DeliveryStatus.READ
                    ? copy(MessageStatus.READ, false, attemptCount, providerMessageId, null, sentAt, deliveredAt, at,
                            now)
                    : null;
            case PENDING_APPROVAL, APPROVED, QUEUED, READ, REJECTED, CANCELLED, FAILED -> null;
        };
        if (next == null) {
            var ignored = copy(status, outcomeUnknown, attemptCount, providerMessageId, failureCategory, sentAt,
                    deliveredAt, readAt, now);
            return new DeliveryOutcome.Ignored(ignored, new MessageEvent(UUID.randomUUID(), tenantId, id,
                    ignored.version(), MessageEventType.DELIVERY_UPDATED, status, status, false,
                    MessageActorKind.PROVIDER, null, null, null, now));
        }
        return new DeliveryOutcome.Applied(new Transition(next, event(next, MessageEventType.DELIVERY_UPDATED,
                MessageActorKind.PROVIDER, null, next.failureCategory(), null, now)));
    }

    /**
     * AC-14: a provider supplied time drives sent_at and the follow up cadence anchor, so it is kept inside
     * [createdAt, now]. A future value would hide the customer from the due queue; a value before the message
     * existed would pull the anchor backwards. Values inside the window are trusted as given.
     */
    private Instant boundReportTime(Instant reported, Instant now) {
        if (reported.isAfter(now)) {
            return now;
        }
        if (reported.isBefore(createdAt)) {
            return createdAt;
        }
        return reported;
    }

    /** True while the message blocks another submit for the same customer. */
    public boolean isOpen() {
        return status.open();
    }

    public boolean isTerminal() {
        return status.terminal();
    }

    /** Overflow is reported as STALE_VERSION, as the follow up module does for its own version counter. */
    public long nextVersion() {
        try {
            return Math.incrementExact(version);
        } catch (ArithmeticException exception) {
            throw new MessagingRefusedException(MessagingErrorCode.STALE_VERSION);
        }
    }

    private void requireState(MessageStatus expected) {
        if (status != expected) {
            throw new MessagingRefusedException(MessagingErrorCode.INVALID_TRANSITION);
        }
    }

    private Transition member(MessageStatus to, MessageEventType type, UUID byMembershipId, Instant now,
            FailureCategory category) {
        Objects.requireNonNull(byMembershipId, "Membership id is required");
        Objects.requireNonNull(now, "Time is required");
        var next = copy(to, false, attemptCount, providerMessageId, category, sentAt, deliveredAt, readAt, now);
        return new Transition(next, event(next, type, MessageActorKind.MEMBER, byMembershipId, category, null, now));
    }

    private Transition failed(FailureCategory category, Instant now) {
        var next = copy(MessageStatus.FAILED, false, attemptCount, providerMessageId, category, sentAt, deliveredAt,
                readAt, now);
        return new Transition(next, event(next, MessageEventType.SEND_FAILED, MessageActorKind.SYSTEM, null,
                category, attemptCount, now));
    }

    private MessageEvent event(OutboundMessage next, MessageEventType type, MessageActorKind actor,
            UUID membershipId, FailureCategory category, Integer attemptNumber, Instant now) {
        return new MessageEvent(UUID.randomUUID(), tenantId, id, next.version(), type, status, next.status(), true,
                actor, membershipId, category, attemptNumber, now);
    }

    private OutboundMessage copy(MessageStatus newStatus, boolean unknown, int attempts, String providerId,
            FailureCategory category, Instant sent, Instant delivered, Instant read, Instant now) {
        Objects.requireNonNull(now, "Time is required");
        return new OutboundMessage(id, tenantId, customerId, contactId, recipientPhone, channel, newStatus, unknown,
                action, templateIntent, locale, origin, body, sourcePolicyVersion, sendKey, attempts, providerId,
                category, sent, delivered, read, createdByMembershipId, createdByActorId, createdAt, now,
                nextVersion());
    }
}
