package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Composes an authorized deterministic evaluation with optional advisory AI output,
 * strictly validated by the deterministic {@link AiActionGate}.
 */
public final class FollowUpRecommendationService {
    private final AiProvider provider;
    private final RecommendationContextAssembler assembler;
    private final AiActionGate gate;

    public FollowUpRecommendationService(AiProvider provider, RecommendationContextAssembler assembler,
            AiActionGate gate) {
        this.provider = Objects.requireNonNull(provider, "AI provider is required");
        this.assembler = Objects.requireNonNull(assembler, "Context assembler is required");
        this.gate = Objects.requireNonNull(gate, "AI action gate is required");
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

        ActionGateDecision gateDecision = gate.evaluate(customerId, assembly, outcome);
        FollowUpEvaluation effectiveEvaluation = gateDecision.evaluation().orElse(evaluation);
        if (gateDecision instanceof ActionGateDecision.Accepted accepted) {
            return FollowUpDecision.accepted(effectiveEvaluation, accepted);
        }
        return FollowUpDecision.rejected(effectiveEvaluation, gateDecision);
    }

    public FollowUpDecision recommend(FollowUpEvaluation evaluation, Duration timeout) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(timeout, "Timeout is required");
        return recommend(evaluation.customerId(), timeout);
    }
}
