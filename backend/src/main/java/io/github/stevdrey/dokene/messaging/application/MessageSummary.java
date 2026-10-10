// ABOUTME: One row of the message list: message fields without the body, the customer's name and contactRemoved.
// ABOUTME: Read straight from SQL under RLS; the controller maps it one to one into the list item representation.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import java.time.Instant;
import java.util.UUID;

public record MessageSummary(UUID messageId, UUID customerId, String customerDisplayName, UUID contactId,
        String recipientPhone, MessageStatus status, boolean outcomeUnknown, SemanticAction action,
        SemanticTemplateIntent templateIntent, String locale, MessageOrigin origin, boolean contactRemoved,
        Instant createdAt, Instant updatedAt, long version) {
}
