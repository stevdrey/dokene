// ABOUTME: Everything the message detail representation shows: the message, its derived contactRemoved flag, its
// ABOUTME: decision, its attempts and the template it would be sent with, read from one consistent state of the message.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.messaging.application.ApprovalBindingAssembler.ReviewedTemplate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** {@code template} is present only while the message awaits approval or dispatch (see MessageTemplatePreviews). */
public record MessageDetail(OutboundMessage message, boolean contactRemoved, Optional<MessageApproval> approval,
        List<SendAttempt> attempts, Optional<ReviewedTemplate> template) {
    public MessageDetail {
        Objects.requireNonNull(message, "Message is required");
        Objects.requireNonNull(approval, "Approval option is required");
        Objects.requireNonNull(template, "Template option is required");
        attempts = List.copyOf(Objects.requireNonNull(attempts, "Attempts are required"));
    }
}
