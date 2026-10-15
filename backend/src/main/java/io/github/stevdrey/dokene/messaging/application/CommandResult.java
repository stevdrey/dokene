// ABOUTME: Outcome of a message command: the message detail read in the command's own transaction, the child record
// ABOUTME: it created, and whether it was new. A replay returns the same shape with created=false.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import java.util.Objects;
import java.util.Optional;

public record CommandResult(MessageDetail detail, Optional<Object> record, boolean created) {
    public CommandResult {
        Objects.requireNonNull(detail, "Message detail is required");
        Objects.requireNonNull(record, "Record option is required");
    }

    public OutboundMessage message() {
        return detail.message();
    }
}
