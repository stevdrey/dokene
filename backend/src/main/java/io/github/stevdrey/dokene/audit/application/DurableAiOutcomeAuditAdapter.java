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
                detail(reason));
    }

    @Override
    public void failed(CustomerId customerId, AiOperation operation, AiUnavailableReason reason) {
        recorder.aiInvocationOutcome(customerId.value(), operation(operation), AiAuditOutcome.FAILED,
                detail(reason));
    }

    // The mappings below are exhaustive switches so that adding a value to a source vocabulary fails the build
    // instead of failing at runtime inside the failure path.
    private static AiAuditOperation operation(AiOperation operation) {
        return switch (operation) {
            case NEXT_BEST_ACTION -> AiAuditOperation.NEXT_BEST_ACTION;
            case MESSAGE_DRAFT -> AiAuditOperation.MESSAGE_DRAFT;
        };
    }

    private static AiAuditDetail detail(ActionGateRejectionReason reason) {
        return switch (reason) {
            case NO_TENANT_CONTEXT, UNAUTHORIZED, CUSTOMER_NOT_FOUND ->
                    throw new IllegalArgumentException("Tenant-boundary rejections must not be audited with a customer id");
            case CUSTOMER_ARCHIVED -> AiAuditDetail.CUSTOMER_ARCHIVED;
            case DO_NOT_CONTACT -> AiAuditDetail.DO_NOT_CONTACT;
            case NO_CONTACT_CONSENT -> AiAuditDetail.NO_CONTACT_CONSENT;
            case FOLLOW_UP_INELIGIBLE -> AiAuditDetail.FOLLOW_UP_INELIGIBLE;
            case STALE_STATE -> AiAuditDetail.STALE_STATE;
            case DISALLOWED_ACTION -> AiAuditDetail.DISALLOWED_ACTION;
            case DISALLOWED_TEMPLATE_INTENT -> AiAuditDetail.DISALLOWED_TEMPLATE_INTENT;
            case INVALID_RECOMMENDATION -> AiAuditDetail.INVALID_RECOMMENDATION;
        };
    }

    /** Only provider/context failures are FAILED outcomes; gate rejections are reported through gateRejected. */
    private static AiAuditDetail detail(AiUnavailableReason reason) {
        return switch (reason) {
            case TIMEOUT -> AiAuditDetail.TIMEOUT;
            case THROTTLED -> AiAuditDetail.THROTTLED;
            case UNAVAILABLE -> AiAuditDetail.UNAVAILABLE;
            case INVALID_STRUCTURED_RESPONSE -> AiAuditDetail.INVALID_STRUCTURED_RESPONSE;
            case REJECTED_REQUEST -> AiAuditDetail.REJECTED_REQUEST;
            case CANCELLED -> AiAuditDetail.CANCELLED;
            case NOT_AVAILABLE -> AiAuditDetail.NOT_AVAILABLE;
            case REFUSED -> AiAuditDetail.REFUSED;
            case CONTEXT_TOO_LARGE -> AiAuditDetail.CONTEXT_TOO_LARGE;
            case CONTEXT_UNSUPPORTED -> AiAuditDetail.CONTEXT_UNSUPPORTED;
            case DISALLOWED_ACTION, DISALLOWED_TEMPLATE_INTENT, INVALID_RECOMMENDATION ->
                    throw new IllegalArgumentException("Gate rejections are not failed AI invocations");
        };
    }
}
