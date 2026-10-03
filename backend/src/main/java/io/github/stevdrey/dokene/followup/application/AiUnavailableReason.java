package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.RecommendationContextException;

/**
 * Closed, client-safe explanation for an {@code AI_UNAVAILABLE} result. Names are a stable API contract;
 * they never carry provider or exception text. {@link #retryable()} tells a client whether trying again later
 * is reasonable; the deterministic follow-up workflow is usable regardless of the value.
 */
public enum AiUnavailableReason {
    TIMEOUT(true),
    THROTTLED(true),
    UNAVAILABLE(true),
    INVALID_STRUCTURED_RESPONSE(false),
    REJECTED_REQUEST(false),
    CANCELLED(false),
    NOT_AVAILABLE(false),
    REFUSED(false),
    DISALLOWED_ACTION(false),
    DISALLOWED_TEMPLATE_INTENT(false),
    INVALID_RECOMMENDATION(false),
    CONTEXT_TOO_LARGE(false),
    CONTEXT_UNSUPPORTED(false);

    private final boolean retryable;

    AiUnavailableReason(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    /** Exhaustive on purpose: a new {@link AiFailureCategory} must be mapped here or the build fails. */
    public static AiUnavailableReason from(AiFailureCategory category) {
        return switch (category) {
            case TIMEOUT -> TIMEOUT;
            case THROTTLED -> THROTTLED;
            case UNAVAILABLE -> UNAVAILABLE;
            case INVALID_STRUCTURED_RESPONSE -> INVALID_STRUCTURED_RESPONSE;
            case REJECTED_REQUEST -> REJECTED_REQUEST;
            case CANCELLED -> CANCELLED;
            case NOT_AVAILABLE -> NOT_AVAILABLE;
            case REFUSED -> REFUSED;
        };
    }

    public static AiUnavailableReason from(RecommendationContextException.Reason reason) {
        return switch (reason) {
            case TOO_LARGE -> CONTEXT_TOO_LARGE;
            case UNSUPPORTED -> CONTEXT_UNSUPPORTED;
        };
    }

    /** Only the model-output rejections that surface as AI_UNAVAILABLE map here. */
    public static AiUnavailableReason from(ActionGateRejectionReason reason) {
        return switch (reason) {
            case DISALLOWED_ACTION -> DISALLOWED_ACTION;
            case DISALLOWED_TEMPLATE_INTENT -> DISALLOWED_TEMPLATE_INTENT;
            case INVALID_RECOMMENDATION -> INVALID_RECOMMENDATION;
            default -> throw new IllegalArgumentException("Gate reason does not map to an AI unavailable reason");
        };
    }
}
