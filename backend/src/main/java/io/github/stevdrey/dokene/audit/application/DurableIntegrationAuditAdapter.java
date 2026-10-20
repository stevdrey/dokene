package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.integration.application.IntegrationAuditPort;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DurableIntegrationAuditAdapter implements IntegrationAuditPort {
    private final AuditRecorder recorder;

    public DurableIntegrationAuditAdapter(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void updated(UUID integrationId, boolean enabled) {
        recorder.integrationUpdated(integrationId, enabled);
    }

    @Override
    public void outboundSwitchChanged(UUID integrationId, boolean enabled) {
        recorder.outboundKillSwitchChanged(integrationId, enabled);
    }

    @Override
    public void mappingRejected() {
        recorder.authorizationDenied(TenantPermission.INTEGRATION_MANAGE, AuditDenialReason.MAPPING_REJECTED);
    }

    @Override
    public void mappingThrottled() {
        recorder.authorizationDenied(TenantPermission.INTEGRATION_MANAGE, AuditDenialReason.RATE_LIMITED);
    }
}
