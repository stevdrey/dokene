package io.github.stevdrey.dokene.ai.application;

import java.time.Duration;

/**
 * Provider-neutral outbound port. The caller assembles context and enforces authorization and policy.
 * Implementations must validate structured output, honor the request timeout, preserve interruption,
 * and map provider failures to {@link AiProviderException}. Invocation has no customer-facing side effect.
 * A concrete adapter owns one reusable provider client rather than constructing a client per call.
 */
public interface AiProvider {
    AiRecommendationResponse recommend(AiRecommendationRequest request);

    default AiDraftResponse draft(AiDraftRequest request) {
        throw new UnsupportedOperationException("Draft generation not supported by this provider");
    }

    default Duration defaultTimeout() {
        return Duration.ofSeconds(15);
    }

    default Duration maxTimeout() {
        return Duration.ofSeconds(30);
    }
}
