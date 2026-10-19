// ABOUTME: Builds the approval fingerprint binding from authoritative state: resolved template, rendered parameters,
// ABOUTME: consent revision and sender identity (ADR 0024 §2). Submit checks renderability; approval and send bind.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver.ResolvedTemplateMapping;
import io.github.stevdrey.dokene.template.domain.TemplateParameterFormat;
import io.github.stevdrey.dokene.template.domain.TemplateParameterValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Every input comes from stored, human-submitted message fields and from the tenant's current records, never from
 * the request or from AI output. Callers hold the customer lock, so what is bound is what the transaction commits
 * against. Values are never logged or echoed: a refusal carries a closed code only.
 */
@Component
public class ApprovalBindingAssembler {
    private final TemplateMappingResolver templates;
    private final ContactPolicyRepository contactPolicies;

    public ApprovalBindingAssembler(TemplateMappingResolver templates, ContactPolicyRepository contactPolicies) {
        this.templates = Objects.requireNonNull(templates, "Template resolver is required");
        this.contactPolicies = Objects.requireNonNull(contactPolicies, "Contact policy repository is required");
    }

    /**
     * The closed vocabulary a template parameter name may use. A mapping naming anything else cannot be rendered
     * deterministically, so it is not sendable ({@code TEMPLATE_NOT_MAPPED}).
     */
    public enum TemplateParameter {
        /** The customer's current display name. Renaming the customer voids open approvals. */
        CUSTOMER_NAME("customer_name"),
        /** The approved message body, exactly as submitted. */
        MESSAGE_BODY("message_body");

        private final String parameterName;

        TemplateParameter(String parameterName) {
            this.parameterName = parameterName;
        }

        public String parameterName() {
            return parameterName;
        }

        static Optional<TemplateParameter> named(String name) {
            for (TemplateParameter parameter : values()) {
                if (parameter.parameterName.equals(name)) {
                    return Optional.of(parameter);
                }
            }
            return Optional.empty();
        }

        String render(String body, Customer customer) {
            return switch (this) {
                case CUSTOMER_NAME -> customer.displayName();
                case MESSAGE_BODY -> body;
            };
        }
    }

    /** A sendable mapping and the exact values its parameters would receive. */
    private record Rendered(ResolvedTemplateMapping mapping, List<String> values) {
    }

    /**
     * Submit (T1): the same template, language and value checks approval applies, so a message that could never be
     * approved is refused before it takes the customer's one open-message slot. Refuses with the codes of
     * {@link #bind}, except consent, which submit checks itself.
     */
    public void requireRenderable(SemanticTemplateIntent intent, String locale, String body, Customer customer) {
        render(Objects.requireNonNull(intent, "Template intent is required"),
                Objects.requireNonNull(locale, "Locale is required"), Objects.requireNonNull(body, "Body is required"),
                Objects.requireNonNull(customer, "Customer is required"));
    }

    /**
     * Refuses with {@code TEMPLATE_NOT_MAPPED} when the intent has no sendable mapping, the template language does not
     * match the message locale or a parameter is outside the vocabulary; {@code BODY_REJECTED} when a rendered value
     * breaks the provider's parameter rules; {@code NO_CONTACT_CONSENT} when the contact's WhatsApp consent is not
     * currently granted.
     */
    public ApprovalFingerprint.Binding bind(OutboundMessage message, Customer customer,
            ApprovalFingerprint.SenderRevision sender) {
        Objects.requireNonNull(message, "Message is required");
        Objects.requireNonNull(customer, "Customer is required");
        Objects.requireNonNull(sender, "Sender is required");
        if (!customer.id().value().equals(message.customerId())
                || !customer.tenantId().value().equals(message.tenantId())) {
            throw new IllegalArgumentException("Customer does not own the message");
        }
        Rendered rendered = render(message.templateIntent(), message.locale(), message.body(), customer);
        ResolvedTemplateMapping mapping = rendered.mapping();
        long consentRevision = contactPolicies.whatsAppConsentRevision(customer, message.contactId())
                .orElseThrow(() -> refuse(MessagingErrorCode.NO_CONTACT_CONSENT));
        return new ApprovalFingerprint.Binding(message.tenantId(), message.id(), message.customerId(),
                message.contactId(), message.recipientPhone(), message.channel(), message.action(),
                message.templateIntent(), message.locale(), message.body(), message.sourcePolicyVersion(),
                consentRevision, new ApprovalFingerprint.TemplateRevision(mapping.version(),
                        mapping.providerTemplateName(), mapping.language(), parameterFormat(mapping.parameterFormat()),
                        mapping.parameterNames()),
                rendered.values(), sender);
    }

    /** The one place a mapping is resolved and rendered, for submit, approval and send alike. */
    private Rendered render(SemanticTemplateIntent intent, String locale, String body, Customer customer) {
        if (!(templates.resolve(intent) instanceof TemplateMappingResolver.Resolved(
                ResolvedTemplateMapping mapping))) {
            throw refuse(MessagingErrorCode.TEMPLATE_NOT_MAPPED);
        }
        if (!sameLanguage(mapping.language(), locale)) {
            throw refuse(MessagingErrorCode.TEMPLATE_NOT_MAPPED);
        }
        var values = new ArrayList<String>(mapping.parameterNames().size());
        for (String name : mapping.parameterNames()) {
            TemplateParameter parameter = TemplateParameter.named(name)
                    .orElseThrow(() -> refuse(MessagingErrorCode.TEMPLATE_NOT_MAPPED));
            values.add(parameter.render(body, customer));
        }
        try {
            return new Rendered(mapping, TemplateParameterValues.validate(mapping.parameterNames(), values));
        } catch (IllegalArgumentException rejected) {
            throw refuse(MessagingErrorCode.BODY_REJECTED);
        }
    }

    /** Exhaustive, so a new template format cannot reach a fingerprint without a deliberate mapping here. */
    private static ApprovalFingerprint.ParameterFormat parameterFormat(TemplateParameterFormat format) {
        return switch (format) {
            case POSITIONAL -> ApprovalFingerprint.ParameterFormat.POSITIONAL;
            case NAMED -> ApprovalFingerprint.ParameterFormat.NAMED;
        };
    }

    /**
     * Meta language codes ({@code es}, {@code es_MX}) differ from the BCP 47 message locale ({@code es-419}); the
     * template must at least be written in the message's language (ADR 0025, Consequences).
     */
    private static boolean sameLanguage(String templateLanguage, String locale) {
        return primarySubtag(templateLanguage, '_').equals(primarySubtag(locale, '-'));
    }

    private static String primarySubtag(String code, char separator) {
        int index = code.indexOf(separator);
        return (index < 0 ? code : code.substring(0, index)).toLowerCase(Locale.ROOT);
    }

    private static MessagingRefusedException refuse(MessagingErrorCode code) {
        return new MessagingRefusedException(code);
    }
}
