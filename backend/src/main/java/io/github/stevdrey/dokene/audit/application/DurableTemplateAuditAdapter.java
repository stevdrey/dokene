package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.template.application.TemplateAuditPort;
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
}
