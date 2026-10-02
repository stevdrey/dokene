package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.audit.domain.AiAuditDetail;
import io.github.stevdrey.dokene.audit.domain.AiAuditOperation;
import io.github.stevdrey.dokene.audit.domain.AiAuditOutcome;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.ActionGateRejectionReason;
import io.github.stevdrey.dokene.followup.application.AiOutcomeAuditPort;
import io.github.stevdrey.dokene.followup.application.AiUnavailableReason;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Maps AI lifecycle outcomes onto the closed audit vocabulary; enum names are intentionally identical. */
@Component
public class DurableAiOutcomeAuditAdapter implements AiOutcomeAuditPort {
    private final AuditRecorder recorder;

    public DurableAiOutcomeAuditAdapter(AuditRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "Audit recorder is required");
    }

    @Override
    public void generated(CustomerId customerId, AiOperation operation) {
        recorder.aiInvocationOutcome(customerId.value(), operation(operation), AiAuditOutcome.GENERATED,
                AiAuditDetail.NONE);
    }

    @Override
    public void modelRefused(CustomerId customerId, AiOperation operation) {
        recorder.aiInvocationOutcome(customerId.value(), operation(operation), AiAuditOutcome.MODEL_REFUSED,
                AiAuditDetail.NONE);
    }

    @Override
    public void gateRejected(CustomerId customerId, AiOperation operation, ActionGateRejectionReason reason) {
        recorder.aiInvocationOutcome(customerId.value(), operation(operation), AiAuditOutcome.GATE_REJECTED,
                AiAuditDetail.valueOf(reason.name()));
    }

    @Override
    public void failed(CustomerId customerId, AiOperation operation, AiUnavailableReason reason) {
        recorder.aiInvocationOutcome(customerId.value(), operation(operation), AiAuditOutcome.FAILED,
                AiAuditDetail.valueOf(reason.name()));
    }

    private static AiAuditOperation operation(AiOperation operation) {
        return AiAuditOperation.valueOf(operation.name());
    }
}
