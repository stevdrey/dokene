// ABOUTME: Everything one response about a message shows: the aggregate, its customer name, child records and flags.
// ABOUTME: The single read model behind detail, mutation and replay responses, so a column cannot leak by accident.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageCancellation;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MessageDetail(OutboundMessage message, String customerDisplayName, Optional<MessageApproval> approval,
        Optional<MessageCancellation> cancellation, List<SendAttempt> attempts, boolean contactRemoved) {
    public MessageDetail {
        Objects.requireNonNull(message, "Message is required");
        Objects.requireNonNull(customerDisplayName, "Customer display name is required");
        Objects.requireNonNull(approval, "Approval is required");
        Objects.requireNonNull(cancellation, "Cancellation is required");
        attempts = List.copyOf(Objects.requireNonNull(attempts, "Attempts are required"));
    }

    /** The attempt with the highest number, which ADR 0023 §4.4 tells clients to read. */
    public Optional<SendAttempt> lastAttempt() {
        return attempts.stream().max(Comparator.comparingInt(SendAttempt::attemptNumber));
    }
}
