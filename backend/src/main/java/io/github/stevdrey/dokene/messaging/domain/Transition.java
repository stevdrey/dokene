// ABOUTME: The result of an accepted command: the next message state and the event that records it.
// ABOUTME: The event sequence number always equals the next message version.
package io.github.stevdrey.dokene.messaging.domain;

import java.util.Objects;

public record Transition(OutboundMessage next, MessageEvent event) {
    public Transition {
        Objects.requireNonNull(next, "Next state is required");
        Objects.requireNonNull(event, "Event is required");
        if (event.sequenceNumber() != next.version() || !event.messageId().equals(next.id())) {
            throw new IllegalArgumentException("Event does not match the next state");
        }
    }
}
