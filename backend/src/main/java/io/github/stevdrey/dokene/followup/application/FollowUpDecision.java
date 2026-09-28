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
        if (gateDecision instanceof ActionGateDecision.Accepted accepted) {
            if (!Objects.equals(recommendation, accepted.outcome())) {
                throw new RecommendationValidationException("recommendation",
                        "Recommendation outcome must match the gate-approved outcome");
            }
            if (gateDecision.currentEvaluation() == null) {
                throw new RecommendationValidationException("gateDecision",
                        "Accepted gate decision must carry an evaluation");
            }
            if (!Objects.equals(evaluation.customerId(), gateDecision.currentEvaluation().customerId())) {
                throw new RecommendationValidationException("evaluation",
                        "Decision evaluation customer ID must match the gate decision customer ID");
            }
            if (!Objects.equals(evaluation, gateDecision.currentEvaluation())) {
                throw new RecommendationValidationException("evaluation",
                        "Decision evaluation must match the accepted gate decision evaluation");
            }
        } else {
            if (recommendation != null) {
                throw new RecommendationValidationException("gateDecision",
                        "Cannot associate a recommendation outcome with a rejected gate decision");
            }
            if (gateDecision.currentEvaluation() != null
                    && !Objects.equals(evaluation.customerId(), gateDecision.currentEvaluation().customerId())) {
                throw new RecommendationValidationException("evaluation",
                        "Decision evaluation customer ID must match the gate decision customer ID");
            }
        }
        if (!evaluation.eligible() && recommendation instanceof ActionRecommendation) {
            throw new RecommendationValidationException("eligible",
                    "Cannot associate an action recommendation with a deterministically ineligible customer");
        }
    }

    public static FollowUpDecision ineligible(FollowUpEvaluation evaluation, Long policyVersion) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        if (evaluation.eligible()) {
            throw new IllegalArgumentException("Customer is deterministically eligible");
        }
        return new FollowUpDecision(evaluation, null,
                ActionGateDecision.rejected(ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE, "Customer is deterministically ineligible", evaluation, policyVersion));
    }

    public static FollowUpDecision ineligible(FollowUpEvaluation evaluation) {
        return ineligible(evaluation, (Long) null);
    }

    public static FollowUpDecision gated(FollowUpEvaluation evaluation, ActionGateDecision gateDecision) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(gateDecision, "Gate decision is required");
        FollowUpEvaluation effective = gateDecision.currentEvaluation() != null ? gateDecision.currentEvaluation() : evaluation;
        if (gateDecision instanceof ActionGateDecision.Accepted accepted) {
            return new FollowUpDecision(effective, accepted.outcome(), gateDecision);
        }
        return new FollowUpDecision(effective, null, gateDecision);
    }

    public static FollowUpDecision accepted(ActionGateDecision.Accepted gateDecision) {
        Objects.requireNonNull(gateDecision, "Gate decision is required");
        if (gateDecision.currentEvaluation() == null) {
            throw new IllegalArgumentException("Accepted gate decision must carry an evaluation");
        }
        return new FollowUpDecision(gateDecision.currentEvaluation(), gateDecision.outcome(), gateDecision);
    }

    public static FollowUpDecision accepted(FollowUpEvaluation evaluation, ActionGateDecision.Accepted gateDecision) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(gateDecision, "Gate decision is required");
        if (gateDecision.currentEvaluation() == null) {
            throw new IllegalArgumentException("Accepted gate decision must carry an evaluation");
        }
        return new FollowUpDecision(evaluation, gateDecision.outcome(), gateDecision);
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

    public Optional<RecommendationOutcome> rawOutcome() {
        return gateDecision.rawOutcome();
    }

    public boolean hasActionRecommendation() {
        return gateDecision.isAccepted() && recommendation instanceof ActionRecommendation;
    }

    public Optional<ActionGateRejectionReason> rejectionReason() {
        return gateDecision.rejectionReason();
    }
}
