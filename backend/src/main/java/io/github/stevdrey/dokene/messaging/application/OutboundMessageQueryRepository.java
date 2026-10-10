// ABOUTME: Read side of the messaging tables: the detail read model and the paged list with the customer join.
// ABOUTME: Every query runs under the signed tenant context; RLS on the joined tables is the backstop.
package io.github.stevdrey.dokene.messaging.application;

import java.util.Optional;
import java.util.UUID;

public interface OutboundMessageQueryRepository {
    Optional<MessageDetail> findDetail(UUID tenantId, UUID messageId);

    MessagePage findPage(UUID tenantId, MessageListQuery query);
}
