package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import java.time.Duration;
import java.util.Objects;

/** Composes an already authorized deterministic evaluation with optional advisory AI output. */
public final class FollowUpRecommendationService {
    private final AiProvider provider;

    public FollowUpRecommendationService(AiProvider provider) {
        this.provider = Objects.requireNonNull(provider, "AI provider is required");
    }

    public FollowUpDecision recommend(FollowUpEvaluation evaluation, Duration timeout) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(timeout, "Timeout is required");
        if (!evaluation.eligible()) {
            return FollowUpDecision.ineligible(evaluation);
        }
        AiRecommendationRequest request = new AiRecommendationRequest(AiOperation.NEXT_BEST_ACTION,
                new RecommendationContext(evaluation.tenantDate(), evaluation.effectiveCadenceDays(),
                        evaluation.lastPurchaseAt()), timeout);
        RecommendationOutcome outcome = provider.recommend(request).outcome();
        return new FollowUpDecision(evaluation, outcome);
    }
}
