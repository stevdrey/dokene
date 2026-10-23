// ABOUTME: Builds the approval fingerprint binding from authoritative state: resolved template, rendered parameters,
// ABOUTME: consent revision and sender identity (ADR 0024 §2). Submit checks renderability; approval and send bind.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingLocales;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver.ResolvedTemplateMapping;
import io.github.stevdrey.dokene.template.domain.TemplateMapping;
import io.github.stevdrey.dokene.template.domain.TemplateParameterFormat;
import io.github.stevdrey.dokene.template.domain.TemplateParameterValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Every input comes from stored, human-submitted message fields and from the tenant's current records, never from
 * the request or from AI output. Callers hold the customer lock, so what is bound is what the transaction commits
 * against. Values are never logged or echoed: a refusal carries a closed code only.
 */
@Component
public class ApprovalBindingAssembler {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)\\}\\}");

    private final TemplateMappingResolver templates;
    private final ContactPolicyRepository contactPolicies;

    public ApprovalBindingAssembler(TemplateMappingResolver templates, ContactPolicyRepository contactPolicies) {
        this.templates = Objects.requireNonNull(templates, "Template resolver is required");
        this.contactPolicies = Objects.requireNonNull(contactPolicies, "Contact policy repository is required");
    }

    /** The value a vocabulary parameter takes for this message (TemplateMapping.Parameter). */
    private static String render(TemplateMapping.Parameter parameter, String body, Customer customer) {
        return switch (parameter) {
            case CUSTOMER_NAME -> customer.displayName();
            case MESSAGE_BODY -> body;
        };
    }

    /** A sendable mapping and the exact values its parameters would receive. */
    private record Rendered(ResolvedTemplateMapping mapping, List<String> values) {
    }

    /**
     * What review shows for a message: the template it would be sent with, the values its parameters render to, the
     * body as the recipient reads it, and the revision an approval must name (issue 268).
     */
    public record ReviewedTemplate(ResolvedTemplateMapping mapping, List<String> values, String renderedBody,
            String revision) {
        public ReviewedTemplate {
            Objects.requireNonNull(mapping, "Mapping is required");
            values = List.copyOf(Objects.requireNonNull(values, "Values are required"));
            Objects.requireNonNull(renderedBody, "Rendered body is required");
            Objects.requireNonNull(revision, "Revision is required");
        }
    }

    /**
     * The template review would show for this message, rendered from the same current records approval binds; empty
     * when the message could not be approved as it stands (no sendable mapping in its language, a value the provider
     * would reject, a body that does not match a fixed template).
     */
    public Optional<ReviewedTemplate> review(OutboundMessage message, Customer customer) {
        Objects.requireNonNull(message, "Message is required");
        Objects.requireNonNull(customer, "Customer is required");
        try {
            Rendered rendered = render(message.templateIntent(), message.locale(), message.body(), customer);
            return Optional.of(new ReviewedTemplate(rendered.mapping(), rendered.values(),
                    renderedFixedBody(rendered.mapping(), rendered.values()),
                    ApprovalFingerprint.reviewRevision(revision(rendered.mapping()), rendered.values())));
        } catch (MessagingRefusedException refused) {
            return Optional.empty();
        }
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
     * breaks the provider's parameter rules or a template without {@code message_body} would not send the body;
     * {@code NO_CONTACT_CONSENT} when the contact's WhatsApp consent is not currently granted.
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
                consentRevision, revision(mapping),
                rendered.values(), sender);
    }

    /** The one place a mapping is resolved and rendered, for submit, approval and send alike. */
    private Rendered render(SemanticTemplateIntent intent, String locale, String body, Customer customer) {
        if (!(templates.resolve(intent) instanceof TemplateMappingResolver.Resolved(
                ResolvedTemplateMapping mapping))) {
            throw refuse(MessagingErrorCode.TEMPLATE_NOT_MAPPED);
        }
        if (!MessagingLocales.acceptsTemplateLanguage(locale, mapping.language())) {
            throw refuse(MessagingErrorCode.TEMPLATE_NOT_MAPPED);
        }
        var values = new ArrayList<String>(mapping.parameterNames().size());
        for (String name : mapping.parameterNames()) {
            TemplateMapping.Parameter parameter = TemplateMapping.Parameter.named(name)
                    .orElseThrow(() -> refuse(MessagingErrorCode.TEMPLATE_NOT_MAPPED));
            values.add(render(parameter, body, customer));
        }
        // A template that does not carry the body sends only its fixed text, with any other parameter filled in, so
        // the body must be exactly that rendered text: what is reviewed, safety-checked and approved is then what the
        // recipient reads (ADR 0025 §2a).
        if (!mapping.parameterNames().contains(TemplateMapping.Parameter.MESSAGE_BODY.parameterName())
                && !body.equals(renderedFixedBody(mapping, values))) {
            throw refuse(MessagingErrorCode.BODY_REJECTED);
        }
        try {
            return new Rendered(mapping, TemplateParameterValues.validate(mapping.parameterNames(), values));
        } catch (IllegalArgumentException rejected) {
            throw refuse(MessagingErrorCode.BODY_REJECTED);
        }
    }

    /**
     * The fixed body with each placeholder replaced by its bound value, in one pass, so a value that itself looks like
     * a placeholder is never substituted again. Positional {@code {{i}}} takes the i-th value; named {@code {{name}}}
     * takes that parameter's value. The verified schema guarantees every placeholder has a value; a placeholder it
     * does not name is left as written rather than guessed, so a review read never fails on it.
     */
    private static String renderedFixedBody(ResolvedTemplateMapping mapping, List<String> values) {
        Matcher placeholder = PLACEHOLDER.matcher(mapping.content().body());
        StringBuilder rendered = new StringBuilder();
        while (placeholder.find()) {
            int index = valueIndex(mapping, placeholder.group(1));
            String replacement = index >= 0 && index < values.size() ? values.get(index) : placeholder.group();
            placeholder.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        placeholder.appendTail(rendered);
        return rendered.toString();
    }

    private static int valueIndex(ResolvedTemplateMapping mapping, String token) {
        if (mapping.parameterFormat() == TemplateParameterFormat.NAMED) {
            return mapping.parameterNames().indexOf(token);
        }
        return token.chars().allMatch(Character::isDigit) && token.length() <= 2 ? Integer.parseInt(token) - 1 : -1;
    }

    /**
     * The template revision an approval binds, and whose digest a review preview shows: the reviewer approves by
     * naming it, so a template changed after review cannot be approved unseen (issue 268).
     */
    public static ApprovalFingerprint.TemplateRevision revision(ResolvedTemplateMapping mapping) {
        Objects.requireNonNull(mapping, "Mapping is required");
        return new ApprovalFingerprint.TemplateRevision(mapping.version(), mapping.providerTemplateName(),
                mapping.language(), parameterFormat(mapping.parameterFormat()), mapping.parameterNames(),
                mapping.content().header(), mapping.content().body(), mapping.content().footer(),
                mapping.content().buttons().stream()
                        .map(button -> new ApprovalFingerprint.ButtonRevision(button.type().name(), button.text(),
                                button.value()))
                        .toList());
    }

    /** Exhaustive, so a new template format cannot reach a fingerprint without a deliberate mapping here. */
    private static ApprovalFingerprint.ParameterFormat parameterFormat(TemplateParameterFormat format) {
        return switch (format) {
            case POSITIONAL -> ApprovalFingerprint.ParameterFormat.POSITIONAL;
            case NAMED -> ApprovalFingerprint.ParameterFormat.NAMED;
        };
    }

    private static MessagingRefusedException refuse(MessagingErrorCode code) {
        return new MessagingRefusedException(code);
    }
}
