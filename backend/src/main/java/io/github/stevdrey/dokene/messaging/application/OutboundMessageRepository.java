// ABOUTME: Persistence port for the message aggregate and its child records; no update path for identity columns.
// ABOUTME: Every method runs under the signed tenant context; RLS is the backstop.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageCancellation;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public interface OutboundMessageRepository {

    /** Message and customer ids resolved from a provider message id, read without a lock. */
    record ProviderMessageRef(UUID messageId, UUID customerId) {
    }

    /**
     * A message with its derived {@code contactRemoved} flag: true when the customer no longer owns the snapshotted
     * contact (spec 0001, contact lifecycle rule).
     */
    record ListedMessage(OutboundMessage message, boolean contactRemoved) {
        public ListedMessage {
            Objects.requireNonNull(message, "Message is required");
        }
    }

    /** A STARTED attempt older than the recovery cutoff, identified by ids only (ADR 0028 §5). */
    record ExpiredClaimRef(UUID tenantId, UUID messageId, UUID attemptId) {
        public ExpiredClaimRef {
            Objects.requireNonNull(tenantId, "Tenant id is required");
            Objects.requireNonNull(messageId, "Message id is required");
            Objects.requireNonNull(attemptId, "Attempt id is required");
        }
    }

    /** Translates a hit on the one open message per customer index into MESSAGE_ALREADY_OPEN. */
    OutboundMessage insert(OutboundMessage message);

    Optional<OutboundMessage> findById(UUID tenantId, UUID messageId);

    Optional<OutboundMessage> findByIdForUpdate(UUID tenantId, UUID messageId);

    Optional<ListedMessage> findListedById(UUID tenantId, UUID messageId);

    /**
     * Ordered {@code createdAt DESC, id DESC}, strictly after {@code after} when given. An empty {@code statuses} set
     * means every status.
     */
    List<ListedMessage> findPage(UUID tenantId, Set<MessageStatus> statuses, Optional<UUID> customerId,
            Optional<MessageCursor> after, int fetchLimit);

    Optional<ProviderMessageRef> findIdsByProviderMessageId(UUID tenantId, String providerMessageId);

    Optional<OutboundMessage> findOpenByCustomer(UUID tenantId, UUID customerId, Optional<UUID> excludingMessageId);

    /** Predicated on the expected version; a miss refuses with STALE_VERSION. */
    OutboundMessage update(OutboundMessage expected, OutboundMessage next);

    MessageApproval insertApproval(MessageApproval approval);

    Optional<MessageApproval> findApproval(UUID tenantId, UUID approvalId);

    /** The message's single decision, if one was made. */
    Optional<MessageApproval> findApprovalByMessage(UUID tenantId, UUID messageId);

    MessageCancellation insertCancellation(MessageCancellation cancellation);

    Optional<MessageCancellation> findCancellation(UUID tenantId, UUID cancellationId);

    SendAttempt insertAttempt(SendAttempt attempt);

    SendAttempt updateAttempt(SendAttempt attempt);

    List<SendAttempt> findAttempts(UUID tenantId, UUID messageId);

    /**
     * The only cross-tenant read of attempts: STARTED claims that began before {@code startedBefore}, ordered by attempt
     * id after {@code afterAttemptId} (from the start when empty), at most {@code limit}. Runs without a tenant context
     * through a security-definer function that returns ids only.
     */
    List<ExpiredClaimRef> findExpiredClaims(Instant startedBefore, Optional<UUID> afterAttemptId, int limit);

    /**
     * Runs {@code run} only while no other application instance runs dispatch recovery: a session advisory lock is
     * held on one dedicated connection for the whole run. Empty, without running, when another instance holds it.
     */
    <T> Optional<T> runAsSoleRecoveryRunner(Supplier<T> run);

    /**
     * Serializes the send policy's rate bound per tenant until the transaction ends, so concurrent sends cannot all
     * pass the count below before any of them inserts its attempt.
     */
    void acquireSendBudgetLock(UUID tenantId);

    /** Attempts started at or after {@code since}, whatever their outcome: every one may have reached the provider. */
    int countAttemptsStartedSince(UUID tenantId, Instant since);

    /** The earliest start among the attempts counted above; it bounds when the window frees a slot. */
    Optional<Instant> oldestAttemptStartedSince(UUID tenantId, Instant since);

    /** True when another message to this customer was sent ({@code sent_at} set) at or after {@code since}. */
    boolean sentToCustomerSince(UUID tenantId, UUID customerId, Instant since);

    MessageEvent appendEvent(MessageEvent event);

    /** Ordered by sequence number. */
    List<MessageEvent> findEvents(UUID tenantId, UUID messageId);
}
