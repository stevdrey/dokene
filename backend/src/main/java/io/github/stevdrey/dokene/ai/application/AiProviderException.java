package io.github.stevdrey.dokene.ai.application;

import java.time.Duration;
import java.util.Objects;

/** Safe, normalized provider failure. Provider payloads and raw error messages must not be included. */
public final class AiProviderException extends RuntimeException {
    private final AiFailureCategory category;
    private final AiInvocationMetadata metadata;
    private final Duration retryAfter;

    public AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata) {
        this(category, metadata, null);
    }

    /**
     * @param retryAfter provider-supplied delay before another attempt is reasonable (e.g. HTTP 429 Retry-After),
     *                   or null when unknown. A numeric hint only; it never carries provider text.
     */
    public AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata, Duration retryAfter) {
        super("AI provider invocation failed: " + Objects.requireNonNull(category, "Failure category is required"));
        this.category = category;
        this.metadata = Objects.requireNonNull(metadata, "Invocation metadata is required");
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("Retry-after hint cannot be negative");
        }
        this.retryAfter = retryAfter;
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

    /** Provider-suggested delay before retrying, or null when the provider gave none. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
