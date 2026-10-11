// ABOUTME: Maps integration configuration events onto the closed audit vocabulary (spec 0005 AC-4, AC-5).
// ABOUTME: Exhaustive switch from IntegrationField to IntegrationAuditField so a new field fails the build.
package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.audit.domain.IntegrationAuditField;
import io.github.stevdrey.dokene.integration.config.IntegrationAuditPort;
import io.github.stevdrey.dokene.integration.config.IntegrationField;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DurableIntegrationAuditAdapter implements IntegrationAuditPort {
    private final AuditRecorder recorder;

    public DurableIntegrationAuditAdapter(AuditRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "Audit recorder is required");
    }

    @Override
    public void integrationUpdated(UUID tenantId, boolean enabled, Set<IntegrationField> changedFields) {
        Objects.requireNonNull(changedFields, "Changed fields are required");
        EnumSet<IntegrationAuditField> fields = EnumSet.noneOf(IntegrationAuditField.class);
        changedFields.stream().map(DurableIntegrationAuditAdapter::field).forEach(fields::add);
        recorder.integrationUpdated(tenantId, new AuditMetadata.IntegrationUpdate(enabled, fields));
    }

    @Override
    public void outboundSwitchChanged(UUID tenantId, boolean enabled) {
        recorder.outboundKillSwitchChanged(tenantId, enabled);
    }

    static IntegrationAuditField field(IntegrationField field) {
        return switch (field) {
            case PHONE_NUMBER_ID -> IntegrationAuditField.PHONE_NUMBER_ID;
            case BUSINESS_ACCOUNT_ID -> IntegrationAuditField.BUSINESS_ACCOUNT_ID;
            case DISPLAY_PHONE_NUMBER -> IntegrationAuditField.DISPLAY_PHONE_NUMBER;
            case SECRET_REF -> IntegrationAuditField.SECRET_REF;
            case ENABLED -> IntegrationAuditField.ENABLED;
        };
    }
}
