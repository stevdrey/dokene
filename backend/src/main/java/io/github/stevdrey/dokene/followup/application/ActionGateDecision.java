package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
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

    record Accepted(RecommendationOutcome outcome) implements ActionGateDecision {
        public Accepted {
            Objects.requireNonNull(outcome, "Outcome is required");
        }

        @Override
        public boolean isAccepted() {
            return true;
        }
    }

    record Rejected(ActionGateRejectionReason reason, String diagnosticMessage) implements ActionGateDecision {
        public Rejected {
            Objects.requireNonNull(reason, "Rejection reason is required");
            Objects.requireNonNull(diagnosticMessage, "Diagnostic message is required");
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
        return new Accepted(outcome);
    }

    static ActionGateDecision rejected(ActionGateRejectionReason reason, String diagnosticMessage) {
        return new Rejected(reason, diagnosticMessage);
    }
}
