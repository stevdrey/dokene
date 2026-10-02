package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.ai.application.AiCorrelationSource;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Exposes the request-scoped audit correlation to the AI boundary without coupling it to the audit module. */
@Component
final class AuditAiCorrelationSource implements AiCorrelationSource {
    private final AuditExecutionContext execution;

    AuditAiCorrelationSource(AuditExecutionContext execution) {
        this.execution = execution;
    }

    @Override
    public Optional<UUID> current() {
        return execution.current();
    }
}
