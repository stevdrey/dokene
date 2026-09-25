package io.github.stevdrey.dokene.ai.application;

/**
 * Provider-neutral outbound port. The caller assembles context and enforces authorization and policy.
 * Implementations must validate structured output, honor the request timeout, preserve interruption,
 * and map provider failures to {@link AiProviderException}. Invocation has no customer-facing side effect.
 * A concrete adapter owns one reusable provider client rather than constructing a client per call.
 */
public interface AiProvider {
    AiRecommendationResponse recommend(AiRecommendationRequest request);
}
