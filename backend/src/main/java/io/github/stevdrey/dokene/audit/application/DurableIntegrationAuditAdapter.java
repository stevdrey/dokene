package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.integration.application.IntegrationAuditPort;
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
}
