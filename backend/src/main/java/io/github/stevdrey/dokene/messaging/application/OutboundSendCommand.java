// ABOUTME: Everything a provider adapter needs for one send: recipient, resolved template, parameters and send key.
// ABOUTME: The send key is forwarded as an idempotency token when a provider accepts one.
package io.github.stevdrey.dokene.messaging.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OutboundSendCommand(String recipientPhone, String providerTemplateName, String language,
        List<String> parameters, String locale, UUID sendKey) {
    public OutboundSendCommand {
        Objects.requireNonNull(recipientPhone, "Recipient phone is required");
        Objects.requireNonNull(providerTemplateName, "Provider template name is required");
        Objects.requireNonNull(language, "Template language is required");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "Parameters are required"));
        Objects.requireNonNull(locale, "Locale is required");
        Objects.requireNonNull(sendKey, "Send key is required");
    }
}
