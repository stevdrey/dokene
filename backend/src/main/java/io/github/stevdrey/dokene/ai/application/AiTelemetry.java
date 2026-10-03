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

    /** Logical result of one AI invocation (request), independent of how many provider attempts it took. */
    enum Outcome { GENERATED, MODEL_REFUSED, GATE_REJECTED, FAILED }

    /** One provider attempt finished. {@code category} is null when the attempt succeeded. */
    void attemptCompleted(AiOperation operation, AiInvocationMetadata metadata, AiFailureCategory category);

    /**
     * The terminal outcome of one invocation: counted exactly once per request, not once per retry attempt.
     * {@code metadata} is the validated metadata of the terminal provider call (the successful response, or the
     * terminal failure); it is null only when no provider was reached (e.g. context assembly failed), which is
     * reported as provider/model {@code none}. Provider/model must never come from request text.
     */
    void outcome(AiOperation operation, AiInvocationMetadata metadata, Outcome outcome);

    /** A bounded retry was scheduled after a transient failure of the given category. */
    void retryScheduled(AiOperation operation, String providerId, AiFailureCategory category);

    /** The model returned a valid explicit "no recommendation / no draft" outcome. */
    void modelRefusal(AiOperation operation);

    /** The deterministic Action Gate rejected otherwise valid provider output. */
    void gateRejected(AiOperation operation, String reason);

    static AiTelemetry noop() {
        return new AiTelemetry() {
            @Override
            public void attemptCompleted(AiOperation operation, AiInvocationMetadata metadata,
                    AiFailureCategory category) {
            }

            @Override
            public void outcome(AiOperation operation, AiInvocationMetadata metadata, Outcome outcome) {
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
