package io.github.stevdrey.dokene.ai.application;

/**
 * Privacy-safe AI operational telemetry port. Implementations must only emit closed-vocabulary values
 * (operation, provider/model identifiers validated by {@link AiInvocationMetadata}, outcome, category,
 * gate reason). Tenant, customer, actor, correlation or any free text must never become a metric dimension.
 */
public interface AiTelemetry {

    /**
     * Closed vocabulary accepted for the {@code reason} tag of gate rejections. It mirrors the follow-up module's
     * {@code ActionGateRejectionReason} (the AI module cannot depend on it); a test in the follow-up module fails
     * if the two drift apart. Any other value is reported as {@link #UNKNOWN_REASON}, never as a raw tag value.
     */
    java.util.Set<String> GATE_REJECTION_REASONS = java.util.Set.of(
            "NO_TENANT_CONTEXT", "UNAUTHORIZED", "CUSTOMER_NOT_FOUND", "CUSTOMER_ARCHIVED", "DO_NOT_CONTACT",
            "NO_CONTACT_CONSENT", "FOLLOW_UP_INELIGIBLE", "STALE_STATE", "DISALLOWED_ACTION",
            "DISALLOWED_TEMPLATE_INTENT", "INVALID_RECOMMENDATION");

    String UNKNOWN_REASON = "UNKNOWN";

    /** One provider attempt finished. {@code category} is null when the attempt succeeded. */
    void invocationCompleted(AiOperation operation, AiInvocationMetadata metadata, AiFailureCategory category);

    /** A bounded retry was scheduled after a transient failure of the given category. */
    void retryScheduled(AiOperation operation, String providerId, AiFailureCategory category);

    /** The model returned a valid explicit "no recommendation / no draft" outcome. */
    void modelRefusal(AiOperation operation);

    /** The deterministic Action Gate rejected otherwise valid provider output. */
    void gateRejected(AiOperation operation, String reason);

    static AiTelemetry noop() {
        return new AiTelemetry() {
            @Override
            public void invocationCompleted(AiOperation operation, AiInvocationMetadata metadata,
                    AiFailureCategory category) {
            }

            @Override
            public void retryScheduled(AiOperation operation, String providerId, AiFailureCategory category) {
            }

            @Override
            public void modelRefusal(AiOperation operation) {
            }

            @Override
            public void gateRejected(AiOperation operation, String reason) {
            }
        };
    }
}
