package io.github.stevdrey.dokene.ai.application;

/**
 * Privacy-safe AI operational telemetry port. Implementations must only emit closed-vocabulary values
 * (operation, provider/model identifiers validated by {@link AiInvocationMetadata}, outcome, category,
 * gate reason). Tenant, customer, actor, correlation or any free text must never become a metric dimension.
 */
public interface AiTelemetry {

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
