// ABOUTME: Deliberate violation fixture for ModuleDependencyArchitectureTest: a followup class that calls the AI provider
// ABOUTME: directly and so skips the rate limit and the tenant token budget. Never loaded by production code.
package io.github.stevdrey.dokene.followup.archfixture;

import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;

@SuppressWarnings("unused")
public final class DirectAiProviderCallFixture {
    private DirectAiProviderCallFixture() {
    }

    static AiRecommendationResponse unbudgeted(AiProvider provider, AiRecommendationRequest request) {
        return provider.recommend(request);
    }
}
