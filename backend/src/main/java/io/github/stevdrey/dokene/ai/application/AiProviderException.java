package io.github.stevdrey.dokene.ai.application;

import java.util.Objects;

/** Safe, normalized provider failure. Provider payloads and raw error messages must not be included. */
public final class AiProviderException extends RuntimeException {
    private final AiFailureCategory category;
    private final AiInvocationMetadata metadata;

    public AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata) {
        super("AI provider invocation failed: " + Objects.requireNonNull(category, "Failure category is required"));
        this.category = category;
        this.metadata = Objects.requireNonNull(metadata, "Invocation metadata is required");
        AiCompletionStatus expected = category == AiFailureCategory.CANCELLED
                ? AiCompletionStatus.CANCELLED : AiCompletionStatus.FAILED;
        if (metadata.status() != expected) {
            throw new IllegalArgumentException("Failure metadata has inconsistent completion status");
        }
    }

    public AiFailureCategory category() {
        return category;
    }

    public AiInvocationMetadata metadata() {
        return metadata;
    }
}
