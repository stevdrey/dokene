package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.template.application.TemplateAuditPort;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DurableTemplateAuditAdapter implements TemplateAuditPort {
    private final AuditRecorder recorder;

    public DurableTemplateAuditAdapter(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void updated(UUID mappingId, boolean enabled) {
        recorder.templateMappingUpdated(mappingId, enabled);
    }

    @Override
    public void refreshThrottled() {
        recorder.authorizationDenied(TenantPermission.TEMPLATE_WRITE, AuditDenialReason.PROVIDER_CALL_LIMITED);
    }
}
