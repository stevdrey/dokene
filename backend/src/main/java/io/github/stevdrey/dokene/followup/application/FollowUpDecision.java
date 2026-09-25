package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.ai.domain.RecommendationValidationException;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import java.util.Objects;
import java.util.Optional;

/**
 * Combines authoritative deterministic follow-up state with untrusted advisory AI output.
 * The evaluation remains the sole source of eligibility and its supporting facts.
 */
public record FollowUpDecision(FollowUpEvaluation evaluation, RecommendationOutcome recommendation) {

    public FollowUpDecision {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        if (!evaluation.eligible() && recommendation instanceof ActionRecommendation) {
            throw new RecommendationValidationException("eligible",
                    "Cannot associate an action recommendation with a deterministically ineligible customer");
        }
    }

    public static FollowUpDecision ineligible(FollowUpEvaluation evaluation) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        if (evaluation.eligible()) {
            throw new IllegalArgumentException("Customer is deterministically eligible; use recommended() or noRecommendation()");
        }
        return new FollowUpDecision(evaluation, null);
    }

    public static FollowUpDecision recommended(FollowUpEvaluation evaluation, ActionRecommendation recommendation) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(recommendation, "Recommendation is required");
        if (!evaluation.eligible()) {
            throw new RecommendationValidationException("eligible",
                    "Cannot recommend action for deterministically ineligible customer");
        }
        return new FollowUpDecision(evaluation, recommendation);
    }

    public static FollowUpDecision noRecommendation(FollowUpEvaluation evaluation, NoRecommendation noRecommendation) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(noRecommendation, "No-recommendation outcome is required");
        return new FollowUpDecision(evaluation, noRecommendation);
    }

    public Optional<RecommendationOutcome> advisoryRecommendation() {
        return Optional.ofNullable(recommendation);
    }

    public boolean hasActionRecommendation() {
        return recommendation instanceof ActionRecommendation;
    }
}
