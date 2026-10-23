// ABOUTME: A tenant's mapping from one closed semantic intent to a provider template, with verified approval evidence.
// ABOUTME: Operator-editable fields and provider-derived evidence are separate; edits drop the evidence.
package io.github.stevdrey.dokene.template.domain;

import io.github.stevdrey.dokene.ai.domain.DraftSafetyValidator;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TimestampPrecision;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public record TemplateMapping(UUID id, TenantId tenantId, UUID integrationId, SemanticTemplateIntent intent,
        String providerTemplateName, String language, List<String> parameterNames, boolean enabled,
        ApprovalEvidence approval, Instant createdAt, Instant updatedAt, long version) {
    /** Meta's template naming grammar (ADR 0023 §3.2). */
    public static final Pattern PROVIDER_TEMPLATE_NAME = Pattern.compile("^[a-z0-9_]{1,512}$");
    /** Meta template language codes, for example {@code es} or {@code es_MX}. */
    public static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(_[A-Z]{2})?$");
    public static final Pattern PARAMETER_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    public static final int MAX_PARAMETERS = 10;
    /**
     * The closed vocabulary a parameter name may use to be rendered when sending; a mapping naming anything else is
     * never sendable. A mapping without {@link #MESSAGE_BODY} sends only the template's fixed text, which must then
     * be a submittable message body.
     */
    public enum Parameter {
        /** The customer's current display name. Renaming the customer voids open approvals. */
        CUSTOMER_NAME("customer_name"),
        /** The approved message body, exactly as submitted. */
        MESSAGE_BODY("message_body");

        private final String parameterName;

        Parameter(String parameterName) {
            this.parameterName = parameterName;
        }

        public String parameterName() {
            return parameterName;
        }

        public static Optional<Parameter> named(String name) {
            for (Parameter parameter : values()) {
                if (parameter.parameterName.equals(name)) {
                    return Optional.of(parameter);
                }
            }
            return Optional.empty();
        }
    }

    public TemplateMapping {
        Objects.requireNonNull(id, "Mapping id is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(integrationId, "Integration id is required");
        Objects.requireNonNull(intent, "Intent is required");
        requireMatch(providerTemplateName, PROVIDER_TEMPLATE_NAME, "Provider template name is invalid");
        requireMatch(language, LANGUAGE, "Template language is invalid");
        parameterNames = List.copyOf(Objects.requireNonNull(parameterNames, "Parameter names are required"));
        if (parameterNames.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException("Too many template parameters");
        }
        for (String name : parameterNames) {
            requireMatch(name, PARAMETER_NAME, "Parameter name is invalid");
        }
        if (new HashSet<>(parameterNames).size() != parameterNames.size()) {
            throw new IllegalArgumentException("Parameter names must be unique");
        }
        Objects.requireNonNull(approval, "Approval evidence is required");
        createdAt = TimestampPrecision.normalize(Objects.requireNonNull(createdAt, "Created at is required"));
        updatedAt = TimestampPrecision.normalize(Objects.requireNonNull(updatedAt, "Updated at is required"));
        if (version < 0) {
            throw new IllegalArgumentException("Version is invalid");
        }
    }

    /**
     * What the provider last reported, and for which mapping revision and business account. {@code UNVERIFIED} carries
     * no evidence at all; only {@code APPROVED} carries the parameter format its schema was verified against, and an
     * approval without one was never schema-checked. {@code content} is the fixed template text read in that same
     * schema check; approval without it was recorded before issue 268 and cannot be reviewed, so it is not sendable.
     */
    public record ApprovalEvidence(TemplateApprovalStatus status, Instant checkedAt, String checkedBusinessAccountId,
            Long checkedVersion, TemplateParameterFormat parameterFormat, TemplateContent content) {
        public static final ApprovalEvidence UNVERIFIED = new ApprovalEvidence(TemplateApprovalStatus.UNVERIFIED,
                null, null, null, null, null);

        public ApprovalEvidence {
            Objects.requireNonNull(status, "Status is required");
            boolean unverified = status == TemplateApprovalStatus.UNVERIFIED;
            if (unverified != (checkedAt == null) || unverified != (checkedBusinessAccountId == null)
                    || unverified != (checkedVersion == null)) {
                throw new IllegalArgumentException("Evidence must be complete unless unverified");
            }
            // APPROVED without a format is evidence whose schema was never checked (recorded before issue 218 or by an
            // instance of an earlier release); it is kept as read but is never sendable.
            if (parameterFormat != null && status != TemplateApprovalStatus.APPROVED) {
                throw new IllegalArgumentException("Only approved evidence carries a parameter format");
            }
            if (content != null && parameterFormat == null) {
                throw new IllegalArgumentException("Only schema-checked approval carries template content");
            }
            if (checkedAt != null) {
                checkedAt = TimestampPrecision.normalize(checkedAt);
            }
        }
    }

    public static TemplateMapping create(TenantId tenantId, UUID integrationId, SemanticTemplateIntent intent,
            String providerTemplateName, String language, List<String> parameterNames, boolean enabled, Instant now) {
        return new TemplateMapping(UUID.randomUUID(), tenantId, integrationId, intent, providerTemplateName, language,
                parameterNames, enabled, ApprovalEvidence.UNVERIFIED, now, now, 0);
    }

    /** Any operator edit invalidates earlier provider evidence; the store advances the version on write. */
    public TemplateMapping edit(UUID integrationId, String providerTemplateName, String language,
            List<String> parameterNames, boolean enabled, Instant now) {
        return new TemplateMapping(id, tenantId, integrationId, intent, providerTemplateName, language,
                parameterNames, enabled, ApprovalEvidence.UNVERIFIED, createdAt, now, version);
    }

    /**
     * Evidence for a provider status other than approval, bound to the current revision and the business account that
     * was asked. Approval is only ever recorded through {@link #verifiedApproval}, after its schema was checked.
     */
    public TemplateMapping verified(TemplateApprovalStatus status, String businessAccountId, Instant now) {
        Objects.requireNonNull(status, "Status is required");
        if (status == TemplateApprovalStatus.APPROVED || status.derivedFromSchema()) {
            throw new IllegalArgumentException("Approval evidence requires a verified schema");
        }
        return withEvidence(new ApprovalEvidence(status, now, businessAccountId, version, null, null), now);
    }

    /**
     * The provider approved the template: the evidence is {@code APPROVED} only when its parameter schema matches the
     * stored parameter names, and otherwise the closed {@code SCHEMA_*} outcome, which is never sendable. Approval
     * keeps the template's fixed text and buttons, so review shows what will be sent. A template that does not carry
     * the message body sends its fixed body as the message, so a fixed body longer than a message body may be is
     * unsupported: no message could ever be submitted against it. Fixed text reaches every recipient unchanged and
     * cannot be grounded per customer, so any header, body, footer or button label the safety checks refuse makes the
     * template unsupported too; button targets are structured and shown in review instead.
     */
    public TemplateMapping verifiedApproval(ProviderTemplateSchema schema, String businessAccountId, Instant now) {
        ProviderTemplateSchema.Verdict verdict = Objects.requireNonNull(schema, "Schema is required")
                .check(parameterNames);
        TemplateContent content = verdict.status() == TemplateApprovalStatus.APPROVED
                && schema instanceof ProviderTemplateSchema.BodyParameters body ? body.content() : null;
        if (content != null && (!fixedTextIsSafe(content) || fixedBodyTooLong(content))) {
            verdict = new ProviderTemplateSchema.Verdict(TemplateApprovalStatus.SCHEMA_UNSUPPORTED, null);
            content = null;
        }
        return withEvidence(new ApprovalEvidence(verdict.status(), now, businessAccountId, version, verdict.format(),
                content), now);
    }

    /** Without the message body as a parameter, the fixed body is the message, so it must fit a message body. */
    private boolean fixedBodyTooLong(TemplateContent content) {
        return !parameterNames.contains(Parameter.MESSAGE_BODY.parameterName())
                && content.body().codePointCount(0, content.body().length()) > MessageDraft.MAX_BODY_LENGTH;
    }

    /** Checked in the template's own language, whose Meta code ({@code en_US}) maps to a BCP 47 tag ({@code en-US}). */
    private boolean fixedTextIsSafe(TemplateContent content) {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("body", content.body());
        if (content.header() != null) {
            texts.put("header", content.header());
        }
        if (content.footer() != null) {
            texts.put("footer", content.footer());
        }
        for (int i = 0; i < content.buttons().size(); i++) {
            texts.put("button " + (i + 1), content.buttons().get(i).text());
        }
        return DraftSafetyValidator.validateFixedText(texts, language.replace('_', '-')).isEmpty();
    }

    private TemplateMapping withEvidence(ApprovalEvidence evidence, Instant now) {
        return new TemplateMapping(id, tenantId, integrationId, intent, providerTemplateName, language,
                parameterNames, enabled, evidence, createdAt, now, version);
    }

    /** Evidence that cannot be refreshed is dropped rather than kept as if it were still current. */
    public TemplateMapping unverified(Instant now) {
        return new TemplateMapping(id, tenantId, integrationId, intent, providerTemplateName, language,
                parameterNames, enabled, ApprovalEvidence.UNVERIFIED, createdAt, now, version);
    }

    public TemplateMapping withVersion(long storedVersion) {
        return new TemplateMapping(id, tenantId, integrationId, intent, providerTemplateName, language,
                parameterNames, enabled, approval, createdAt, updatedAt, storedVersion);
    }

    /**
     * Sendable only when enabled, provider-approved for exactly this revision and business account, and the evidence is
     * fresh. Everything else fails closed. Both verified parameter formats are sendable; the format travels with the
     * resolved mapping into the approval fingerprint and the send command. Approval without a format was never
     * schema-checked, and approval without content cannot be reviewed, so neither is sendable. The fixed text is
     * checked again here with the current rules in the template's language, so evidence recorded under an earlier
     * release's rules (an {@code en_US} template checked only with the Spanish vocabulary) is not sendable on its
     * account alone (issue 268).
     */
    public boolean sendable(UUID currentIntegrationId, String currentBusinessAccountId, Instant now, Duration maxAge) {
        return enabled && approval.status() == TemplateApprovalStatus.APPROVED
                && approval.parameterFormat() != null
                && approval.content() != null
                && fixedTextIsSafe(approval.content())
                && approval.checkedVersion() == version
                && integrationId.equals(currentIntegrationId)
                && approval.checkedBusinessAccountId().equals(currentBusinessAccountId)
                && !approval.checkedAt().isAfter(now)
                && Duration.between(approval.checkedAt(), now).compareTo(maxAge) <= 0;
    }

    private static void requireMatch(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(message);
        }
    }
}
