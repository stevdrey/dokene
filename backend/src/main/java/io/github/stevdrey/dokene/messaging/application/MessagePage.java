// ABOUTME: A page of message summaries plus the cursor of the next page, empty on the last page.
package io.github.stevdrey.dokene.messaging.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MessagePage(List<MessageSummary> items, Optional<MessageCursor> next) {
    public MessagePage {
        items = List.copyOf(Objects.requireNonNull(items, "Items are required"));
        Objects.requireNonNull(next, "Next cursor is required");
    }
}
