// ABOUTME: Everything a provider adapter needs for one send: recipient, resolved template, parameters, send key and the
// ABOUTME: sender identity the approval bound; an adapter refuses to send from any other sender (issue 137).
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OutboundSendCommand(String recipientPhone, String providerTemplateName, String language,
        List<String> parameters, String locale, UUID sendKey, SenderRevision expectedSender) {
    public OutboundSendCommand {
        Objects.requireNonNull(recipientPhone, "Recipient phone is required");
        Objects.requireNonNull(providerTemplateName, "Provider template name is required");
        Objects.requireNonNull(language, "Template language is required");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "Parameters are required"));
        Objects.requireNonNull(locale, "Locale is required");
        Objects.requireNonNull(sendKey, "Send key is required");
        Objects.requireNonNull(expectedSender, "Expected sender is required");
    }
}
