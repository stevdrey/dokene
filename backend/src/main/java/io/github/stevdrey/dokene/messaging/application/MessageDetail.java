// ABOUTME: Everything the message detail representation shows: the message, its derived contactRemoved flag, its
// ABOUTME: decision and its attempts, always read from one consistent state of the message.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MessageDetail(OutboundMessage message, boolean contactRemoved, Optional<MessageApproval> approval,
        List<SendAttempt> attempts) {
    public MessageDetail {
        Objects.requireNonNull(message, "Message is required");
        Objects.requireNonNull(approval, "Approval option is required");
        attempts = List.copyOf(Objects.requireNonNull(attempts, "Attempts are required"));
    }
}
