// ABOUTME: The validated inputs of GET /api/messages: status set, optional customer, cursor and a bounded limit.
// ABOUTME: Built by the controller after parsing; the limit range 1 to 100 with default 50 follows ADR 0009.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record MessageListQuery(Set<MessageStatus> statuses, Optional<UUID> customerId, Optional<MessageCursor> cursor,
        int limit) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public MessageListQuery {
        Objects.requireNonNull(statuses, "Statuses are required (empty means every status)");
        Objects.requireNonNull(customerId, "Customer filter is required");
        Objects.requireNonNull(cursor, "Cursor is required");
        statuses = Set.copyOf(statuses);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
