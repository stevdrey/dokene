// ABOUTME: Everything a provider adapter needs for one send: recipient, resolved template, its parameter format, names
// ABOUTME: and values, send key and the sender identity the approval bound; an adapter refuses any other sender.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.ParameterFormat;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code parameterNames} and {@code parameters} are aligned by position: the value at index i fills the placeholder
 * named at index i. A {@code NAMED} template is sent by name, a {@code POSITIONAL} one by order alone.
 */
public record OutboundSendCommand(String recipientPhone, String providerTemplateName, String language,
        ParameterFormat parameterFormat, List<String> parameterNames, List<String> parameters, String locale,
        UUID sendKey, SenderRevision expectedSender) {
    public OutboundSendCommand {
        Objects.requireNonNull(recipientPhone, "Recipient phone is required");
        Objects.requireNonNull(providerTemplateName, "Provider template name is required");
        Objects.requireNonNull(language, "Template language is required");
        Objects.requireNonNull(parameterFormat, "Parameter format is required");
        parameterNames = List.copyOf(Objects.requireNonNull(parameterNames, "Parameter names are required"));
        parameters = List.copyOf(Objects.requireNonNull(parameters, "Parameters are required"));
        if (parameterNames.size() != parameters.size()) {
            throw new IllegalArgumentException("Each template parameter needs exactly one name");
        }
        Objects.requireNonNull(locale, "Locale is required");
        Objects.requireNonNull(sendKey, "Send key is required");
        Objects.requireNonNull(expectedSender, "Expected sender is required");
    }
}
