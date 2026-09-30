package io.github.stevdrey.dokene.ai.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Inbound request to generate a follow-up message draft.
 */
public record AiDraftRequest(
        AiOperation operation,
        DraftContext context,
        Duration timeout) {

    public AiDraftRequest {
        Objects.requireNonNull(operation, "Operation is required");
        Objects.requireNonNull(context, "Draft context is required");
        Objects.requireNonNull(timeout, "Timeout is required");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
    }

    public AiDraftRequest(DraftContext context, Duration timeout) {
        this(AiOperation.MESSAGE_DRAFT, context, timeout);
    }
}
