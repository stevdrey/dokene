package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import java.time.Duration;
import java.util.Objects;

/** Composes an authorized deterministic evaluation with optional advisory AI output. */
public final class FollowUpRecommendationService {
    private final AiProvider provider;
    private final RecommendationContextAssembler assembler;

    public FollowUpRecommendationService(AiProvider provider, RecommendationContextAssembler assembler) {
        this.provider = Objects.requireNonNull(provider, "AI provider is required");
        this.assembler = Objects.requireNonNull(assembler, "Context assembler is required");
    }

    public FollowUpDecision recommend(CustomerId customerId, Duration timeout) {
        Objects.requireNonNull(customerId, "Customer ID is required");
        Objects.requireNonNull(timeout, "Timeout is required");
        var assembly = assembler.assemble(customerId);
        var evaluation = assembly.evaluation();
        if (!evaluation.eligible()) {
            return FollowUpDecision.ineligible(evaluation);
        }
        AiRecommendationRequest request = new AiRecommendationRequest(AiOperation.NEXT_BEST_ACTION,
                assembly.context(), timeout);
        RecommendationOutcome outcome = provider.recommend(request).outcome();
        return new FollowUpDecision(evaluation, outcome);
    }

    public FollowUpDecision recommend(FollowUpEvaluation evaluation, Duration timeout) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(timeout, "Timeout is required");
        if (!evaluation.eligible()) {
            return FollowUpDecision.ineligible(evaluation);
        }
        var assembly = assembler.assemble(evaluation);
        AiRecommendationRequest request = new AiRecommendationRequest(AiOperation.NEXT_BEST_ACTION,
                assembly.context(), timeout);
        RecommendationOutcome outcome = provider.recommend(request).outcome();
        return new FollowUpDecision(evaluation, outcome);
    }
}
