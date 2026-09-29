package io.github.stevdrey.dokene.ai.application;

import io.github.stevdrey.dokene.ai.domain.DraftOutcome;
import java.util.Objects;

/**
 * Normalized provider response containing the advisory draft outcome and invocation metadata.
 */
public record AiDraftResponse(
        DraftOutcome outcome,
        AiInvocationMetadata metadata) {

    public AiDraftResponse {
        Objects.requireNonNull(outcome, "Outcome is required");
        Objects.requireNonNull(metadata, "Metadata is required");
        if (metadata.status() != AiCompletionStatus.SUCCEEDED) {
            throw new IllegalArgumentException("A draft response must have SUCCEEDED status");
        }
    }
}
