package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.domain.DraftOutcome;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import java.util.Objects;
import java.util.Optional;

/**
 * Sealed hierarchy representing the deterministic decision reached by the AI Action Gate for message drafts.
 */
public sealed interface DraftGateDecision permits DraftGateDecision.Accepted, DraftGateDecision.Rejected {

    boolean isAccepted();

    default boolean isRejected() {
        return !isAccepted();
    }

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

    default Optional<DraftOutcome> rawOutcome() {
        return Optional.empty();
    }

    default Long policyVersion() {
        return null;
    }

    record Accepted(DraftOutcome outcome, FollowUpEvaluation currentEvaluation, Long policyVersion) implements DraftGateDecision {
        public Accepted {
            Objects.requireNonNull(outcome, "Outcome is required");
            Objects.requireNonNull(currentEvaluation, "Current evaluation is required");
        }

        public Accepted(DraftOutcome outcome, FollowUpEvaluation currentEvaluation) {
            this(outcome, currentEvaluation, null);
        }

        @Override
        public boolean isAccepted() {
            return true;
        }

        @Override
        public Optional<DraftOutcome> rawOutcome() {
            return Optional.of(outcome);
        }
    }

    record Rejected(
            ActionGateRejectionReason reason,
            String diagnosticMessage,
            FollowUpEvaluation currentEvaluation,
            DraftOutcome rejectedOutcome,
            Long policyVersion
    ) implements DraftGateDecision {
        public Rejected {
            Objects.requireNonNull(reason, "Rejection reason is required");
            Objects.requireNonNull(diagnosticMessage, "Diagnostic message is required");
        }

        public Rejected(
                ActionGateRejectionReason reason,
                String diagnosticMessage,
                FollowUpEvaluation currentEvaluation,
                DraftOutcome rejectedOutcome) {
            this(reason, diagnosticMessage, currentEvaluation, rejectedOutcome, null);
        }

        public Rejected(ActionGateRejectionReason reason, String diagnosticMessage, FollowUpEvaluation currentEvaluation) {
            this(reason, diagnosticMessage, currentEvaluation, null, null);
        }

        public Rejected(ActionGateRejectionReason reason, String diagnosticMessage) {
            this(reason, diagnosticMessage, null, null, null);
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
        public Optional<DraftOutcome> rawOutcome() {
            return Optional.ofNullable(rejectedOutcome);
        }
    }

    static DraftGateDecision accepted(DraftOutcome outcome, FollowUpEvaluation evaluation) {
        return new Accepted(outcome, evaluation, null);
    }

    static DraftGateDecision accepted(DraftOutcome outcome, FollowUpEvaluation evaluation, Long policyVersion) {
        return new Accepted(outcome, evaluation, policyVersion);
    }

    static DraftGateDecision rejected(ActionGateRejectionReason reason, String diagnosticMessage) {
        return new Rejected(reason, diagnosticMessage, null, null, null);
    }

    static DraftGateDecision rejected(ActionGateRejectionReason reason, String diagnosticMessage, FollowUpEvaluation evaluation) {
        return new Rejected(reason, diagnosticMessage, evaluation, null, null);
    }

    static DraftGateDecision rejected(ActionGateRejectionReason reason, String diagnosticMessage, FollowUpEvaluation evaluation, DraftOutcome rawOutcome) {
        return new Rejected(reason, diagnosticMessage, evaluation, rawOutcome, null);
    }

    static DraftGateDecision rejected(ActionGateRejectionReason reason, String diagnosticMessage, FollowUpEvaluation evaluation, DraftOutcome rawOutcome, Long policyVersion) {
        return new Rejected(reason, diagnosticMessage, evaluation, rawOutcome, policyVersion);
    }
}
