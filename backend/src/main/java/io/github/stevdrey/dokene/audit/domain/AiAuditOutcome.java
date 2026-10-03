package io.github.stevdrey.dokene.audit.domain;

/**
 * Lifecycle outcome of one AI invocation. GENERATED and MODEL_REFUSED are SUCCESS, GATE_REJECTED is DENIED
 * and FAILED is FAILURE at the audit-event level.
 */
public enum AiAuditOutcome {
    GENERATED, MODEL_REFUSED, GATE_REJECTED, FAILED
}
