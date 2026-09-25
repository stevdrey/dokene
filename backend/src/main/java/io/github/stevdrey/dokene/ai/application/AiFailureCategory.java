package io.github.stevdrey.dokene.ai.application;

public enum AiFailureCategory {
    TIMEOUT,
    THROTTLED,
    UNAVAILABLE,
    INVALID_STRUCTURED_RESPONSE,
    REJECTED_REQUEST,
    CANCELLED
}
