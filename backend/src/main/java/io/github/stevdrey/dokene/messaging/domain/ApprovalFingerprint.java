// ABOUTME: Binds an approval to what was approved: recipient, content, parameter values, template and sender (ADR 0024 §2).
// ABOUTME: Computed at approval, recomputed before send; any difference refuses the send with APPROVAL_INVALIDATED.
package io.github.stevdrey.dokene.messaging.domain;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record ApprovalFingerprint(String value) {
    /** Domain label of the canonical encoding; a different binding needs a new label, never a reinterpretation. */
    public static final String SCHEME = "approval/v4";

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public ApprovalFingerprint {
        Objects.requireNonNull(value, "Fingerprint value is required");
        if (!SHA256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("Fingerprint must be 64 lowercase hex characters");
        }
    }

    /** Redacted: the value is never returned, audited or logged (ADR 0024 §2). */
    @Override
    public String toString() {
        return "ApprovalFingerprint[redacted]";
    }

    public static ApprovalFingerprint of(Binding binding) {
        Objects.requireNonNull(binding, "Binding is required");
        return new ApprovalFingerprint(RequestFingerprint.digest(SCHEME, binding.canonicalFields()));
    }

    /** True when the stored approval still covers the binding the send is about to use. */
    public boolean covers(Binding current) {
        return equals(of(current));
    }

    /**
     * Everything an approval vouches for. {@code parameterValues} are the exact ordered values the provider
     * receives; the send command carries the values of the binding whose fingerprint just matched. The message
     * version, kill switches and the follow-up policy version are deliberately absent: they are re-checked live by
     * the send policy and must not void approvals on their own. {@code consentRevision} is the contact policy
     * version of the consent history row that started the contact's current WhatsApp GRANTED run (the first GRANTED
     * row after the latest non-GRANTED row). A revocation followed by a re-grant always yields a new value, unlike a
     * timestamp, while re-saving GRANTED over GRANTED does not void approvals.
     */
    public record Binding(UUID tenantId, UUID messageId, UUID customerId, UUID contactId, String recipientPhone,
            MessageChannel channel, SemanticAction action, SemanticTemplateIntent templateIntent, String locale,
            String body, long sourcePolicyVersion, long consentRevision, TemplateRevision template,
            List<String> parameterValues, SenderRevision sender) {
        public Binding {
            Objects.requireNonNull(tenantId, "Tenant id is required");
            Objects.requireNonNull(messageId, "Message id is required");
            Objects.requireNonNull(customerId, "Customer id is required");
            Objects.requireNonNull(contactId, "Contact id is required");
            Objects.requireNonNull(recipientPhone, "Recipient phone is required");
            Objects.requireNonNull(channel, "Channel is required");
            Objects.requireNonNull(action, "Action is required");
            Objects.requireNonNull(templateIntent, "Template intent is required");
            Objects.requireNonNull(locale, "Locale is required");
            Objects.requireNonNull(body, "Body is required");
            Objects.requireNonNull(template, "Template revision is required");
            Objects.requireNonNull(sender, "Sender revision is required");
            // List.copyOf also rejects null elements, so a missing value cannot hash like an empty one.
            parameterValues = List.copyOf(Objects.requireNonNull(parameterValues, "Parameter values are required"));
            if (parameterValues.size() != template.parameterNames().size()) {
                throw new IllegalArgumentException("Each template parameter needs exactly one value");
            }
            if (sourcePolicyVersion < 0) {
                throw new IllegalArgumentException("Source policy version cannot be negative");
            }
            // Granted consent always has at least one history row, whose policy version starts at 1.
            if (consentRevision < 1) {
                throw new IllegalArgumentException("Consent revision starts at 1");
            }
        }

        List<String> canonicalFields() {
            var fields = new ArrayList<String>();
            fields.add(tenantId.toString());
            fields.add(messageId.toString());
            fields.add(customerId.toString());
            fields.add(contactId.toString());
            fields.add(recipientPhone);
            fields.add(channel.name());
            fields.add(action.name());
            fields.add(templateIntent.name());
            fields.add(locale);
            fields.add(body);
            fields.add(Long.toString(sourcePolicyVersion));
            fields.add(Long.toString(consentRevision));
            fields.add(Long.toString(template.version()));
            fields.add(template.providerTemplateName());
            fields.add(template.language());
            fields.add(template.parameterFormat().name());
            fields.add(Integer.toString(template.parameterNames().size()));
            fields.addAll(template.parameterNames());
            // The fixed text the recipient reads; header and footer are optional and encode null distinctly.
            fields.add(template.headerText());
            fields.add(template.bodyText());
            fields.add(template.footerText());
            fields.add(Integer.toString(template.buttons().size()));
            for (ButtonRevision button : template.buttons()) {
                fields.add(button.type());
                fields.add(button.text());
                fields.add(button.value());
            }
            fields.addAll(parameterValues);
            fields.add(sender.integrationId().toString());
            fields.add(sender.phoneNumberId());
            fields.add(sender.businessAccountId());
            fields.add(Long.toString(sender.credentialRevision()));
            return fields;
        }
    }

    /**
     * How the approved template declares its body placeholders. It mirrors the template module's
     * TemplateParameterFormat, because this package cannot depend on the template module.
     */
    public enum ParameterFormat {
        POSITIONAL, NAMED
    }

    /**
     * The template mapping revision the approval was given against, including the parameter format its provider
     * schema was verified to declare, and the template's fixed text and buttons read in that check: a template
     * re-verified under another format, with other text or with other buttons voids the approval. {@code headerText}
     * and {@code footerText} are optional.
     */
    public record TemplateRevision(long version, String providerTemplateName, String language,
            ParameterFormat parameterFormat, List<String> parameterNames, String headerText, String bodyText,
            String footerText, List<ButtonRevision> buttons) {
        /**
         * Meta's naming grammar. The name and language rules mirror TemplateMappingResolver.ResolvedTemplateMapping,
         * because this package cannot depend on the template module.
         */
        private static final Pattern PROVIDER_TEMPLATE_NAME = Pattern.compile("^[a-z0-9_]{1,512}$");

        public TemplateRevision {
            Objects.requireNonNull(providerTemplateName, "Provider template name is required");
            Objects.requireNonNull(language, "Template language is required");
            if (!PROVIDER_TEMPLATE_NAME.matcher(providerTemplateName).matches()) {
                throw new IllegalArgumentException("Provider template name is malformed");
            }
            if (language.isBlank()) {
                throw new IllegalArgumentException("Template language is required");
            }
            Objects.requireNonNull(parameterFormat, "Parameter format is required");
            parameterNames = List.copyOf(Objects.requireNonNull(parameterNames, "Parameter names are required"));
            Objects.requireNonNull(bodyText, "Template body text is required");
            buttons = List.copyOf(Objects.requireNonNull(buttons, "Template buttons are required"));
            if (version < 0) {
                throw new IllegalArgumentException("Template mapping version cannot be negative");
            }
        }
    }

    /**
     * One fixed button of the approved template, in order: its kind, label and target (null for a quick reply). It
     * mirrors the template module's TemplateButton, because this package cannot depend on the template module.
     */
    public record ButtonRevision(String type, String text, String value) {
        public ButtonRevision {
            Objects.requireNonNull(type, "Button type is required");
            Objects.requireNonNull(text, "Button text is required");
        }
    }

    /**
     * The sending identity: changing the integration, its business number or its credential voids the approval.
     * {@code credentialRevision} counts secret reference changes (rotation); it is a number, never the reference.
     */
    public record SenderRevision(UUID integrationId, String phoneNumberId, String businessAccountId,
            long credentialRevision) {
        public SenderRevision {
            Objects.requireNonNull(integrationId, "Integration id is required");
            Objects.requireNonNull(phoneNumberId, "Phone number id is required");
            Objects.requireNonNull(businessAccountId, "Business account id is required");
            if (credentialRevision < 1) {
                throw new IllegalArgumentException("Credential revision starts at 1");
            }
        }
    }
}
