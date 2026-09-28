package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import java.util.Objects;
import java.util.Optional;

/**
 * Sealed hierarchy representing the deterministic decision reached by the AI Action Gate.
 */
public sealed interface ActionGateDecision permits ActionGateDecision.Accepted, ActionGateDecision.Rejected {

    boolean isAccepted();

    default Optional<ActionGateRejectionReason> rejectionReason() {
        return Optional.empty();
    }

    default Optional<String> diagnostic() {
        return Optional.empty();
    }

    FollowUpEvaluation currentEvaluation();

    default Optional<FollowUpEvaluation> evaluation() {
        return Optional.ofNullable(currentEvaluation());
    }

    default Optional<RecommendationOutcome> rawOutcome() {
        return Optional.empty();
    }

    default long policyVersion() {
        return 0L;
    }

    record Accepted(RecommendationOutcome outcome, FollowUpEvaluation currentEvaluation, long policyVersion) implements ActionGateDecision {
        public Accepted {
            Objects.requireNonNull(outcome, "Outcome is required");
            Objects.requireNonNull(currentEvaluation, "Current evaluation is required");
        }

        public Accepted(RecommendationOutcome outcome, FollowUpEvaluation currentEvaluation) {
            this(outcome, currentEvaluation, 0L);
        }

        @Override
        public boolean isAccepted() {
            return true;
        }

        @Override
        public Optional<RecommendationOutcome> rawOutcome() {
            return Optional.of(outcome);
        }
    }

    record Rejected(
            ActionGateRejectionReason reason,
            String diagnosticMessage,
            FollowUpEvaluation currentEvaluation,
            RecommendationOutcome rejectedOutcome,
            long policyVersion
    ) implements ActionGateDecision {
        public Rejected {
            Objects.requireNonNull(reason, "Rejection reason is required");
            Objects.requireNonNull(diagnosticMessage, "Diagnostic message is required");
        }

        public Rejected(
                ActionGateRejectionReason reason,
                String diagnosticMessage,
                FollowUpEvaluation currentEvaluation,
                RecommendationOutcome rejectedOutcome) {
            this(reason, diagnosticMessage, currentEvaluation, rejectedOutcome, 0L);
        }

        public Rejected(ActionGateRejectionReason reason, String diagnosticMessage, FollowUpEvaluation currentEvaluation) {
            this(reason, diagnosticMessage, currentEvaluation, null, 0L);
        }

        public Rejected(ActionGateRejectionReason reason, String diagnosticMessage) {
            this(reason, diagnosticMessage, null, null, 0L);
        }

        @Override
        public boolean isAccepted() {
            return false;
        }

        @Override
        public Optional<ActionGateRejectionReason> rejectionReason() {
            return Optional.of(reason);
        }

        @Override
        public Optional<String> diagnostic() {
            return Optional.of(diagnosticMessage);
        }

        @Override
        public Optional<RecommendationOutcome> rawOutcome() {
            return Optional.ofNullable(rejectedOutcome);
        }
    }

    static ActionGateDecision accepted(RecommendationOutcome outcome, FollowUpEvaluation evaluation) {
        return new Accepted(outcome, evaluation, 0L);
    }

    static ActionGateDecision accepted(RecommendationOutcome outcome, FollowUpEvaluation evaluation, long policyVersion) {
        return new Accepted(outcome, evaluation, policyVersion);
    }

    static ActionGateDecision rejected(ActionGateRejectionReason reason, String diagnosticMessage) {
        return new Rejected(reason, diagnosticMessage, null, null, 0L);
    }

    static ActionGateDecision rejected(ActionGateRejectionReason reason, String diagnosticMessage, FollowUpEvaluation evaluation) {
        return new Rejected(reason, diagnosticMessage, evaluation, null, 0L);
    }

    static ActionGateDecision rejected(ActionGateRejectionReason reason, String diagnosticMessage, FollowUpEvaluation evaluation, RecommendationOutcome rawOutcome) {
        return new Rejected(reason, diagnosticMessage, evaluation, rawOutcome, 0L);
    }

    static ActionGateDecision rejected(ActionGateRejectionReason reason, String diagnosticMessage, FollowUpEvaluation evaluation, long policyVersion) {
        return new Rejected(reason, diagnosticMessage, evaluation, null, policyVersion);
    }

    static ActionGateDecision rejected(ActionGateRejectionReason reason, String diagnosticMessage, FollowUpEvaluation evaluation, RecommendationOutcome rawOutcome, long policyVersion) {
        return new Rejected(reason, diagnosticMessage, evaluation, rawOutcome, policyVersion);
    }
}
