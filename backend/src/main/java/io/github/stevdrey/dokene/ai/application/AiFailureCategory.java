package io.github.stevdrey.dokene.ai.application;

/**
 * Stable, provider-neutral failure taxonomy. Values are part of the client-facing contract
 * ({@code unavailableReason}) and metric tags; do not rename without an ADR.
 */
public enum AiFailureCategory {
    TIMEOUT(true),
    THROTTLED(true),
    UNAVAILABLE(true),
    INVALID_STRUCTURED_RESPONSE(false),
    REJECTED_REQUEST(false),
    CANCELLED(false),
    /** AI is disabled or the operation is unsupported by the configured provider. Never retried. */
    NOT_AVAILABLE(false),
    /** The model explicitly refused or produced no usable result. Never retried. */
    REFUSED(false);

    private final boolean retryable;

    AiFailureCategory(boolean retryable) {
        this.retryable = retryable;
    }

    /** True only for transient failures of side-effect-free generation that may be retried within a bounded budget. */
    public boolean retryable() {
        return retryable;
    }
}
