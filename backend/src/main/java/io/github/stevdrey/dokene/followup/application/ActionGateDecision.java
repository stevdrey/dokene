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

    record Accepted(RecommendationOutcome outcome, FollowUpEvaluation currentEvaluation) implements ActionGateDecision {
        public Accepted {
            Objects.requireNonNull(outcome, "Outcome is required");
        }

        public Accepted(RecommendationOutcome outcome) {
            this(outcome, null);
        }

        @Override
        public boolean isAccepted() {
            return true;
        }
    }

    record Rejected(ActionGateRejectionReason reason, String diagnosticMessage, FollowUpEvaluation currentEvaluation) implements ActionGateDecision {
        public Rejected {
            Objects.requireNonNull(reason, "Rejection reason is required");
            Objects.requireNonNull(diagnosticMessage, "Diagnostic message is required");
        }

        public Rejected(ActionGateRejectionReason reason, String diagnosticMessage) {
            this(reason, diagnosticMessage, null);
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
    }

    static ActionGateDecision accepted(RecommendationOutcome outcome) {
        return new Accepted(outcome, null);
    }

    static ActionGateDecision accepted(RecommendationOutcome outcome, FollowUpEvaluation evaluation) {
        return new Accepted(outcome, evaluation);
    }

    static ActionGateDecision rejected(ActionGateRejectionReason reason, String diagnosticMessage) {
        return new Rejected(reason, diagnosticMessage, null);
    }

    static ActionGateDecision rejected(ActionGateRejectionReason reason, String diagnosticMessage, FollowUpEvaluation evaluation) {
        return new Rejected(reason, diagnosticMessage, evaluation);
    }
}
