// ABOUTME: A tenant's mapping from one closed semantic intent to a provider template, with verified approval evidence.
// ABOUTME: Operator-editable fields and provider-derived evidence are separate; edits drop the evidence.
package io.github.stevdrey.dokene.template.domain;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TimestampPrecision;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
     * no evidence at all.
     */
    public record ApprovalEvidence(TemplateApprovalStatus status, Instant checkedAt, String checkedBusinessAccountId,
            Long checkedVersion) {
        public static final ApprovalEvidence UNVERIFIED = new ApprovalEvidence(TemplateApprovalStatus.UNVERIFIED,
                null, null, null);

        public ApprovalEvidence {
            Objects.requireNonNull(status, "Status is required");
            boolean unverified = status == TemplateApprovalStatus.UNVERIFIED;
            if (unverified != (checkedAt == null) || unverified != (checkedBusinessAccountId == null)
                    || unverified != (checkedVersion == null)) {
                throw new IllegalArgumentException("Evidence must be complete unless unverified");
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

    /** Evidence is bound to the current revision and the business account that was asked. */
    public TemplateMapping verified(TemplateApprovalStatus status, String businessAccountId, Instant now) {
        return new TemplateMapping(id, tenantId, integrationId, intent, providerTemplateName, language,
                parameterNames, enabled, new ApprovalEvidence(status, now, businessAccountId, version), createdAt,
                now, version);
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
     * fresh. Everything else fails closed.
     */
    public boolean sendable(UUID currentIntegrationId, String currentBusinessAccountId, Instant now, Duration maxAge) {
        return enabled && approval.status() == TemplateApprovalStatus.APPROVED
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
