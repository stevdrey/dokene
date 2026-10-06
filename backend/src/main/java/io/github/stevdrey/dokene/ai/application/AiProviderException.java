package io.github.stevdrey.dokene.ai.application;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Safe, normalized provider failure. Provider payloads and raw error messages must not be included. */
public final class AiProviderException extends RuntimeException {
    private final AiFailureCategory category;
    private final AiInvocationMetadata metadata;
    private final Duration retryAfter;
    private final Set<AiOutputRejection> rejections;

    public AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata) {
        this(category, metadata, (Duration) null);
    }

    /**
     * @param retryAfter provider-supplied delay before another attempt is reasonable (e.g. HTTP 429 Retry-After),
     *                   or null when unknown. A numeric hint only; it never carries provider text.
     */
    public AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata, Duration retryAfter) {
        this(category, metadata, retryAfter, (Set<AiOutputRejection>) null);
    }

    /**
     * Failure of {@code INVALID_STRUCTURED_RESPONSE} caused by the adapter's own validation of an otherwise
     * parseable output (see {@link AiOutputRejection}); never carries model text.
     */
    public AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata, AiOutputRejection rejection) {
        this(category, metadata, null, Objects.requireNonNull(rejection, "Rejection detail is required"));
    }

    /**
     * As above for an output that violated several adapter rules at once (for example an action mismatch and unsafe
     * content): every closed reason is kept so no finding is lost; still never carries model text.
     */
    public AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata,
            Set<AiOutputRejection> rejections) {
        this(category, metadata, null, requireNonEmpty(rejections));
    }

    private static Set<AiOutputRejection> requireNonEmpty(Set<AiOutputRejection> rejections) {
        Objects.requireNonNull(rejections, "Rejection details are required");
        if (rejections.isEmpty()) {
            throw new IllegalArgumentException("At least one rejection detail is required");
        }
        return rejections;
    }

    private AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata, Duration retryAfter,
            AiOutputRejection rejection) {
        this(category, metadata, retryAfter, rejection == null ? null : EnumSet.of(rejection));
    }

    private AiProviderException(AiFailureCategory category, AiInvocationMetadata metadata, Duration retryAfter,
            Set<AiOutputRejection> rejections) {
        super("AI provider invocation failed: " + Objects.requireNonNull(category, "Failure category is required"));
        this.category = category;
        this.metadata = Objects.requireNonNull(metadata, "Invocation metadata is required");
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("Retry-after hint cannot be negative");
        }
        this.retryAfter = retryAfter;
        if (rejections != null && category != AiFailureCategory.INVALID_STRUCTURED_RESPONSE) {
            throw new IllegalArgumentException("A rejection detail requires INVALID_STRUCTURED_RESPONSE");
        }
        this.rejections = rejections == null ? Set.of() : Set.copyOf(EnumSet.copyOf(rejections));
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

    /** Primary reason (first by declaration order) the adapter rejected a parseable output, or null if none. */
    public AiOutputRejection rejection() {
        return rejections.isEmpty() ? null : EnumSet.copyOf(rejections).iterator().next();
    }

    /** Every closed reason the adapter rejected a parseable output; empty for a malformed/unusable response. */
    public Set<AiOutputRejection> rejections() {
        return rejections;
    }

    /** Provider-suggested delay before retrying, or null when the provider gave none. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
