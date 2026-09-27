package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.ai.domain.RecommendationValidationException;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import java.util.Objects;
import java.util.Optional;

/**
 * Combines authoritative deterministic follow-up state with untrusted advisory AI output and gate decisions.
 * The evaluation remains the sole source of eligibility, and recommendations are only exposed
 * as actionable if accepted by the deterministic AI Action Gate.
 */
public record FollowUpDecision(
        FollowUpEvaluation evaluation,
        RecommendationOutcome recommendation,
        ActionGateDecision gateDecision) {

    public FollowUpDecision {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(gateDecision, "Gate decision is required");
        if (gateDecision instanceof ActionGateDecision.Rejected && recommendation instanceof ActionRecommendation) {
            throw new RecommendationValidationException("gateDecision",
                    "Cannot associate an action recommendation with a rejected gate decision");
        }
        if (!evaluation.eligible() && recommendation instanceof ActionRecommendation) {
            throw new RecommendationValidationException("eligible",
                    "Cannot associate an action recommendation with a deterministically ineligible customer");
        }
    }

    public FollowUpDecision(FollowUpEvaluation evaluation, RecommendationOutcome recommendation) {
        this(evaluation, recommendation,
                recommendation != null
                        ? ActionGateDecision.accepted(recommendation)
                        : (evaluation != null && evaluation.eligible()
                                ? ActionGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION, "No recommendation provided")
                                : ActionGateDecision.rejected(ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE, "Customer is ineligible for follow-up")));
    }

    public static FollowUpDecision ineligible(FollowUpEvaluation evaluation) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        if (evaluation.eligible()) {
            throw new IllegalArgumentException("Customer is deterministically eligible; use recommended() or noRecommendation()");
        }
        return new FollowUpDecision(evaluation, null,
                ActionGateDecision.rejected(ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE, "Customer is deterministically ineligible"));
    }

    public static FollowUpDecision recommended(FollowUpEvaluation evaluation, ActionRecommendation recommendation) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(recommendation, "Recommendation is required");
        if (!evaluation.eligible()) {
            throw new RecommendationValidationException("eligible",
                    "Cannot recommend action for deterministically ineligible customer");
        }
        return new FollowUpDecision(evaluation, recommendation, ActionGateDecision.accepted(recommendation));
    }

    public static FollowUpDecision noRecommendation(FollowUpEvaluation evaluation, NoRecommendation noRecommendation) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(noRecommendation, "No-recommendation outcome is required");
        return new FollowUpDecision(evaluation, noRecommendation, ActionGateDecision.accepted(noRecommendation));
    }

    public static FollowUpDecision rejected(FollowUpEvaluation evaluation, ActionGateDecision gateDecision) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(gateDecision, "Gate decision is required");
        if (gateDecision.isAccepted()) {
            throw new IllegalArgumentException("Cannot create a rejected decision from an accepted gate decision");
        }
        return new FollowUpDecision(evaluation, null, gateDecision);
    }

    public Optional<RecommendationOutcome> advisoryRecommendation() {
        return gateDecision.isAccepted() ? Optional.ofNullable(recommendation) : Optional.empty();
    }

    public boolean hasActionRecommendation() {
        return gateDecision.isAccepted() && recommendation instanceof ActionRecommendation;
    }

    public Optional<ActionGateRejectionReason> rejectionReason() {
        return gateDecision.rejectionReason();
    }
}
