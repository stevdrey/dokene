package io.github.stevdrey.dokene.ai.application;

import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import java.time.Duration;
import java.util.Objects;

/** Network-free provider fixture with fixed outcomes and diagnostics. */
public final class DeterministicFakeAiProvider implements AiProvider {
    private final RecommendationOutcome outcome;
    private final AiFailureCategory failure;
    private int invocationCount;
    private AiRecommendationRequest lastRequest;

    private DeterministicFakeAiProvider(RecommendationOutcome outcome, AiFailureCategory failure) {
        this.outcome = outcome;
        this.failure = failure;
    }

    public static DeterministicFakeAiProvider success(RecommendationOutcome outcome) {
        return new DeterministicFakeAiProvider(Objects.requireNonNull(outcome), null);
    }

    public static DeterministicFakeAiProvider malformedOutput() {
        return failure(AiFailureCategory.INVALID_STRUCTURED_RESPONSE);
    }

    public static DeterministicFakeAiProvider failure(AiFailureCategory category) {
        return new DeterministicFakeAiProvider(null, Objects.requireNonNull(category));
    }

    @Override
    public AiRecommendationResponse recommend(AiRecommendationRequest request) {
        lastRequest = Objects.requireNonNull(request, "Request is required");
        invocationCount++;
        if (Thread.currentThread().isInterrupted()) {
            throw failure(AiFailureCategory.CANCELLED, AiCompletionStatus.CANCELLED);
        }
        if (failure != null) {
            AiCompletionStatus status = failure == AiFailureCategory.CANCELLED
                    ? AiCompletionStatus.CANCELLED : AiCompletionStatus.FAILED;
            throw failure(failure, status);
        }
        return new AiRecommendationResponse(outcome, metadata(AiCompletionStatus.SUCCEEDED));
    }

    public int invocationCount() {
        return invocationCount;
    }

    public AiRecommendationRequest lastRequest() {
        return lastRequest;
    }

    private AiProviderException failure(AiFailureCategory category, AiCompletionStatus status) {
        return new AiProviderException(category, metadata(status));
    }

    private AiInvocationMetadata metadata(AiCompletionStatus status) {
        return new AiInvocationMetadata("fake", "test-model", "test-request-1",
                Duration.ofMillis(12), new AiTokenUsage(11, 7), status);
    }
}
