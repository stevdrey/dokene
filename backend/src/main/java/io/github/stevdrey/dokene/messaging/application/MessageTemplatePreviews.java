// ABOUTME: Resolves the template a not-yet-dispatched message would be sent with, for review (issue 268).
// ABOUTME: Read-only and fail closed: no sendable, language-compatible mapping means no preview.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingLocales;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver.ResolvedTemplateMapping;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The preview is the tenant's current mapping, the one approval and send bind, so it is only shown while the message
 * still awaits approval or dispatch. Once dispatched, the current mapping may differ from what was sent, so nothing
 * is shown rather than something that may be wrong. It runs inside the caller's tenant context and transaction.
 */
@Component
public class MessageTemplatePreviews {
    private final TemplateMappingResolver templates;

    public MessageTemplatePreviews(TemplateMappingResolver templates) {
        this.templates = Objects.requireNonNull(templates, "Template resolver is required");
    }

    public Optional<ResolvedTemplateMapping> of(OutboundMessage message) {
        Objects.requireNonNull(message, "Message is required");
        if (message.status() != MessageStatus.PENDING_APPROVAL && message.status() != MessageStatus.APPROVED) {
            return Optional.empty();
        }
        if (!(templates.resolve(message.templateIntent()) instanceof TemplateMappingResolver.Resolved(
                ResolvedTemplateMapping mapping))
                || !MessagingLocales.acceptsTemplateLanguage(message.locale(), mapping.language())) {
            return Optional.empty();
        }
        return Optional.of(mapping);
    }
}
