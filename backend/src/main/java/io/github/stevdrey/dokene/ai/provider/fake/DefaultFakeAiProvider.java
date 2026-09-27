package io.github.stevdrey.dokene.ai.provider.fake;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import io.github.stevdrey.dokene.ai.application.AiTokenUsage;
import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic in-memory fake provider for local development, staging environments,
 * and test profiles where an external OpenAI API key is not configured.
 */
public final class DefaultFakeAiProvider implements AiProvider {

    @Override
    public AiRecommendationResponse recommend(AiRecommendationRequest request) {
        Objects.requireNonNull(request, "Request is required");
        RecommendationOutcome outcome = buildOutcome(request);
        AiInvocationMetadata metadata = new AiInvocationMetadata(
                "fake-provider",
                "deterministic-fake",
                "fake-req-1",
                Duration.ofMillis(5),
                new AiTokenUsage(0L, 0L),
                AiCompletionStatus.SUCCEEDED);
        return new AiRecommendationResponse(outcome, metadata);
    }

    private RecommendationOutcome buildOutcome(AiRecommendationRequest request) {
        if (request.context() == null || request.context().trusted() == null) {
            return new NoRecommendation(
                    NoRecommendationReason.INSUFFICIENT_HISTORY,
                    "No trusted context provided",
                    RecommendationConfidence.of(0.5));
        }
        List<SemanticAction> allowed = request.context().trusted().allowedActions();
        if (allowed == null || allowed.isEmpty()) {
            return new NoRecommendation(
                    NoRecommendationReason.NO_RELEVANT_OFFER,
                    "No allowed actions for customer",
                    RecommendationConfidence.of(0.5));
        }

        SemanticAction action = allowed.contains(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP)
                ? SemanticAction.REPEAT_PURCHASE_FOLLOW_UP
                : allowed.getFirst();
        SemanticTemplateIntent intent = action == SemanticAction.REPEAT_PURCHASE_FOLLOW_UP
                ? SemanticTemplateIntent.REPEAT_PURCHASE
                : SemanticTemplateIntent.GENERAL_FOLLOW_UP;

        return new ActionRecommendation(
                action,
                intent,
                "Automated cadence follow-up recommendation",
                RecommendationConfidence.of(0.85),
                DraftVariables.empty());
    }
}
