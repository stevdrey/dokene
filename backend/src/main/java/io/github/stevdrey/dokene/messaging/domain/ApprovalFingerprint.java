// ABOUTME: Binds an approval to exactly what was approved: recipient, content, template and sender revisions (ADR 0024 §2).
// ABOUTME: Computed at approval, recomputed before send; any difference refuses the send with APPROVAL_INVALIDATED.
package io.github.stevdrey.dokene.messaging.domain;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record ApprovalFingerprint(String value) {
    /** Domain label of the canonical encoding; a different binding needs a new label, never a reinterpretation. */
    public static final String SCHEME = "approval/v1";

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public ApprovalFingerprint {
        Objects.requireNonNull(value, "Fingerprint value is required");
        if (!SHA256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("Fingerprint must be 64 lowercase hex characters");
        }
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
     * Everything an approval vouches for. The message version, kill switches and the follow-up policy version are
     * deliberately absent: they are re-checked live by the send policy and must not void approvals on their own.
     */
    public record Binding(UUID tenantId, UUID messageId, UUID customerId, UUID contactId, String recipientPhone,
            MessageChannel channel, SemanticAction action, SemanticTemplateIntent templateIntent, String locale,
            String body, long sourcePolicyVersion, Instant consentChangedAt, TemplateRevision template,
            SenderRevision sender) {
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
            Objects.requireNonNull(consentChangedAt, "Consent revision is required");
            Objects.requireNonNull(template, "Template revision is required");
            Objects.requireNonNull(sender, "Sender revision is required");
            if (sourcePolicyVersion < 0) {
                throw new IllegalArgumentException("Source policy version cannot be negative");
            }
            // Postgres keeps microseconds; truncating here keeps an in-memory and a reloaded binding equal.
            consentChangedAt = consentChangedAt.truncatedTo(ChronoUnit.MICROS);
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
            fields.add(consentChangedAt.toString());
            fields.add(Long.toString(template.version()));
            fields.add(template.providerTemplateName());
            fields.add(template.language());
            fields.add(Integer.toString(template.parameterNames().size()));
            fields.addAll(template.parameterNames());
            fields.add(sender.integrationId().toString());
            fields.add(sender.phoneNumberId());
            fields.add(sender.businessAccountId());
            return fields;
        }
    }

    /** The template mapping revision the approval was given against. */
    public record TemplateRevision(long version, String providerTemplateName, String language,
            List<String> parameterNames) {
        public TemplateRevision {
            Objects.requireNonNull(providerTemplateName, "Provider template name is required");
            Objects.requireNonNull(language, "Template language is required");
            parameterNames = List.copyOf(Objects.requireNonNull(parameterNames, "Parameter names are required"));
            if (version < 0) {
                throw new IllegalArgumentException("Template mapping version cannot be negative");
            }
        }
    }

    /** The sending identity: changing the integration or its business number voids the approval. */
    public record SenderRevision(UUID integrationId, String phoneNumberId, String businessAccountId) {
        public SenderRevision {
            Objects.requireNonNull(integrationId, "Integration id is required");
            Objects.requireNonNull(phoneNumberId, "Phone number id is required");
            Objects.requireNonNull(businessAccountId, "Business account id is required");
        }
    }
}
