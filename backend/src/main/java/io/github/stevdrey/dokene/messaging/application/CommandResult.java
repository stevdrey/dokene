// ABOUTME: Outcome of a message command: the current message, the child record it created, and whether it was new.
// ABOUTME: A replay returns the same shape with created=false.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import java.util.Objects;
import java.util.Optional;

public record CommandResult(OutboundMessage message, Optional<Object> record, boolean created) {
    public CommandResult {
        Objects.requireNonNull(message, "Message is required");
        Objects.requireNonNull(record, "Record option is required");
    }
}
