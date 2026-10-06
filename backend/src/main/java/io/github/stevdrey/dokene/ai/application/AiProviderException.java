package io.github.stevdrey.dokene.ai.application;

import java.time.Duration;
import java.util.Objects;

/** Safe, normalized provider failure. Provider payloads and raw error messages must not be included. */
public final class AiProviderException extends RuntimeException {
    private final AiFailureCategory category;
    private final AiInvocationMetadata metadata;
    private final Duration retryAfter;
    private final AiOutputRejection rejection;

    public AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata) {
        this(category, metadata, (Duration) null);
    }

    /**
     * @param retryAfter provider-supplied delay before another attempt is reasonable (e.g. HTTP 429 Retry-After),
     *                   or null when unknown. A numeric hint only; it never carries provider text.
     */
    public AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata, Duration retryAfter) {
        this(category, metadata, retryAfter, null);
    }

    /**
     * Failure of {@code INVALID_STRUCTURED_RESPONSE} caused by the adapter's own validation of an otherwise
     * parseable output (see {@link AiOutputRejection}); never carries model text.
     */
    public AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata, AiOutputRejection rejection) {
        this(category, metadata, null, Objects.requireNonNull(rejection, "Rejection detail is required"));
    }

    private AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata, Duration retryAfter,
            AiOutputRejection rejection) {
        super("AI provider invocation failed: " + Objects.requireNonNull(category, "Failure category is required"));
        this.category = category;
        this.metadata = Objects.requireNonNull(metadata, "Invocation metadata is required");
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("Retry-after hint cannot be negative");
        }
        this.retryAfter = retryAfter;
        if (rejection != null && category != AiFailureCategory.INVALID_STRUCTURED_RESPONSE) {
            throw new IllegalArgumentException("A rejection detail requires INVALID_STRUCTURED_RESPONSE");
        }
        this.rejection = rejection;
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

    /** Why the adapter rejected a parseable output, or null for a genuinely malformed/unusable response. */
    public AiOutputRejection rejection() {
        return rejection;
    }

    /** Provider-suggested delay before retrying, or null when the provider gave none. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
