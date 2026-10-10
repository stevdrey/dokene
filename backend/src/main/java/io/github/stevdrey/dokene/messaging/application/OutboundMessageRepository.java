// ABOUTME: Persistence port for the message aggregate and its child records; no update path for identity columns.
// ABOUTME: Every method runs under the signed tenant context; RLS is the backstop.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageCancellation;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboundMessageRepository {

    /** Message and customer ids resolved from a provider message id, read without a lock. */
    record ProviderMessageRef(UUID messageId, UUID customerId) {
    }

    /** Translates a hit on the one open message per customer index into MESSAGE_ALREADY_OPEN. */
    OutboundMessage insert(OutboundMessage message);

    Optional<OutboundMessage> findById(UUID tenantId, UUID messageId);

    Optional<OutboundMessage> findByIdForUpdate(UUID tenantId, UUID messageId);

    Optional<ProviderMessageRef> findIdsByProviderMessageId(UUID tenantId, String providerMessageId);

    Optional<OutboundMessage> findOpenByCustomer(UUID tenantId, UUID customerId, Optional<UUID> excludingMessageId);

    /** Predicated on the expected version; a miss refuses with STALE_VERSION. */
    OutboundMessage update(OutboundMessage expected, OutboundMessage next);

    MessageApproval insertApproval(MessageApproval approval);

    Optional<MessageApproval> findApproval(UUID tenantId, UUID approvalId);

    MessageCancellation insertCancellation(MessageCancellation cancellation);

    Optional<MessageCancellation> findCancellation(UUID tenantId, UUID cancellationId);

    SendAttempt insertAttempt(SendAttempt attempt);

    SendAttempt updateAttempt(SendAttempt attempt);

    List<SendAttempt> findAttempts(UUID tenantId, UUID messageId);

    MessageEvent appendEvent(MessageEvent event);

    /** Ordered by sequence number. */
    List<MessageEvent> findEvents(UUID tenantId, UUID messageId);
}
