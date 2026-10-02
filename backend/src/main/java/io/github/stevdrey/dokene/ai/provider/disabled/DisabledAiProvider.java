package io.github.stevdrey.dokene.ai.provider.disabled;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import io.github.stevdrey.dokene.ai.application.AiTokenUsage;

import java.time.Duration;

/**
 * Fallback {@link AiProvider} implementation used when no concrete AI provider
 * is configured. Fails fast with {@link AiFailureCategory#UNAVAILABLE} to allow
 * Dokene to boot and execute deterministic follow-up workflows while safely
 * degrading recommendation requests.
 */
public final class DisabledAiProvider implements AiProvider {

    private static final AiInvocationMetadata METADATA = new AiInvocationMetadata(
            "disabled",
            "none",
            "none",
            Duration.ZERO,
            new AiTokenUsage(0, 0),
            AiCompletionStatus.FAILED);

    @Override
    public AiRecommendationResponse recommend(AiRecommendationRequest request) {
        throw new AiProviderException(AiFailureCategory.UNAVAILABLE, METADATA);
    }

    @Override
    public io.github.stevdrey.dokene.ai.application.AiDraftResponse draft(io.github.stevdrey.dokene.ai.application.AiDraftRequest request) {
        throw new AiProviderException(AiFailureCategory.UNAVAILABLE, METADATA);
    }
}
