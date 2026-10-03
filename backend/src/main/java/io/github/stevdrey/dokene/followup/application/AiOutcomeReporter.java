package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiCorrelationSource;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiTelemetry;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single reporting point for the terminal outcome of an AI invocation: metrics, one safe log line and one
 * durable audit event. Logs contain only operation, outcome, closed-vocabulary detail and correlation id;
 * never prompts, customer text, phone numbers, generated bodies, tenant/customer identifiers or provider messages.
 * Audit persistence failures propagate (ADR 0006): the endpoint fails closed instead of losing the record.
 */
@Component
public final class AiOutcomeReporter {
    private static final Logger log = LoggerFactory.getLogger(AiOutcomeReporter.class);

    private final AiTelemetry telemetry;
    private final AiOutcomeAuditPort audit;
    private final AiCorrelationSource correlation;

    public AiOutcomeReporter(AiTelemetry telemetry, AiOutcomeAuditPort audit, AiCorrelationSource correlation) {
        this.telemetry = Objects.requireNonNull(telemetry, "Telemetry is required");
        this.audit = Objects.requireNonNull(audit, "Audit port is required");
        this.correlation = Objects.requireNonNull(correlation, "Correlation source is required");
    }

    public static AiOutcomeReporter noop() {
        return new AiOutcomeReporter(AiTelemetry.noop(), AiOutcomeAuditPort.noop(), AiCorrelationSource.none());
    }

    public void generated(CustomerId customerId, AiOperation operation, AiInvocationMetadata metadata) {
        telemetry.outcome(operation, metadata, AiTelemetry.Outcome.GENERATED);
        logOutcome(operation, "GENERATED", "NONE");
        audit.generated(customerId, operation);
    }

    public void modelRefused(CustomerId customerId, AiOperation operation, AiInvocationMetadata metadata) {
        telemetry.outcome(operation, metadata, AiTelemetry.Outcome.MODEL_REFUSED);
        telemetry.modelRefusal(operation);
        logOutcome(operation, "MODEL_REFUSED", "NONE");
        audit.modelRefused(customerId, operation);
    }

    public void gateRejected(CustomerId customerId, AiOperation operation, ActionGateRejectionReason reason,
            AiInvocationMetadata metadata) {
        telemetry.outcome(operation, metadata, AiTelemetry.Outcome.GATE_REJECTED);
        telemetry.gateRejected(operation, reason.name());
        logOutcome(operation, "GATE_REJECTED", reason.name());
        // These reasons are raised at the authorization/existence boundary, before the gate has established that
        // the requested customer belongs to the active tenant, so the caller-supplied id may be unverified or
        // foreign. It must not become an audit target (ADR 0006); the gate's own security-rejection audit already
        // records these cases. Metrics and the log above are closed-vocabulary and stay.
        if (!isTenantBoundaryRejection(reason)) {
            audit.gateRejected(customerId, operation, reason);
        }
    }

    public void failed(CustomerId customerId, AiOperation operation, AiUnavailableReason reason,
            AiInvocationMetadata metadata) {
        telemetry.outcome(operation, metadata, AiTelemetry.Outcome.FAILED);
        logOutcome(operation, "FAILED", reason.name());
        audit.failed(customerId, operation, reason);
    }

    private static boolean isTenantBoundaryRejection(ActionGateRejectionReason reason) {
        return switch (reason) {
            case NO_TENANT_CONTEXT, UNAUTHORIZED, CUSTOMER_NOT_FOUND -> true;
            case CUSTOMER_ARCHIVED, DO_NOT_CONTACT, NO_CONTACT_CONSENT, FOLLOW_UP_INELIGIBLE, STALE_STATE,
                    DISALLOWED_ACTION, DISALLOWED_TEMPLATE_INTENT, INVALID_RECOMMENDATION -> false;
        };
    }

    private void logOutcome(AiOperation operation, String outcome, String detail) {
        log.info("AI invocation outcome: operation={}, outcome={}, detail={}, correlationId={}",
                operation, outcome, detail, correlation.current().orElse(null));
    }
}
