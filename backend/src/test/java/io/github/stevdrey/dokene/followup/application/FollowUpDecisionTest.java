package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.RecommendationValidationException;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FollowUpDecisionTest {
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final LocalDate tenantDate = LocalDate.of(2026, 9, 22);
    private final ZoneId zoneId = ZoneId.of("America/Costa_Rica");
    private final Instant evaluatedAt = Instant.parse("2026-09-22T08:00:00Z");

    private final ActionRecommendation sampleAction = new ActionRecommendation(
            SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
            SemanticTemplateIntent.REPEAT_PURCHASE,
            "Customer reached cadence threshold",
            RecommendationConfidence.of(0.9),
            DraftVariables.empty());

    private final NoRecommendation sampleRefusal = new NoRecommendation(
            NoRecommendationReason.INSUFFICIENT_HISTORY,
            "History too sparse",
            RecommendationConfidence.of(0.95));

    @Test
    void gatedDecisionPreservesEvaluationAndAdvisoryAction() {
        FollowUpEvaluation evaluation = evaluation(FollowUpStatus.DUE, FollowUpReason.DUE_TODAY);
        ActionGateDecision.Accepted acceptedGate = new ActionGateDecision.Accepted(sampleAction);

        FollowUpDecision decision = FollowUpDecision.accepted(evaluation, acceptedGate);

        assertThat(decision.evaluation()).isSameAs(evaluation);
        assertThat(decision.evaluation().customerId()).isEqualTo(customerId);
        assertThat(decision.evaluation().status()).isEqualTo(FollowUpStatus.DUE);
        assertThat(decision.evaluation().reasons()).containsExactly(FollowUpReason.DUE_TODAY);
        assertThat(decision.evaluation().tenantDate()).isEqualTo(tenantDate);
        assertThat(decision.evaluation().tenantZone()).isEqualTo(zoneId);
        assertThat(decision.evaluation().evaluatedAt()).isEqualTo(evaluatedAt);
        assertThat(decision.evaluation().eligible()).isTrue();
        assertThat(decision.hasActionRecommendation()).isTrue();
        assertThat(decision.advisoryRecommendation()).contains(sampleAction);
        assertThat(decision.gateDecision().isAccepted()).isTrue();
    }

    @Test
    void rejectedGateDecisionHidesActionRecommendationAndExposesReason() {
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE, FollowUpReason.DUE_TODAY);
        ActionGateDecision rejectedGate = ActionGateDecision.rejected(
                ActionGateRejectionReason.STALE_STATE, "State changed");

        FollowUpDecision decision = FollowUpDecision.rejected(due, rejectedGate);

        assertThat(decision.evaluation()).isSameAs(due);
        assertThat(decision.hasActionRecommendation()).isFalse();
        assertThat(decision.advisoryRecommendation()).isEmpty();
        assertThat(decision.gateDecision().isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.STALE_STATE);
    }

    @Test
    void cannotAssociateActionRecommendationWithRejectedGateDecision() {
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE, FollowUpReason.DUE_TODAY);
        ActionGateDecision rejectedGate = ActionGateDecision.rejected(
                ActionGateRejectionReason.DISALLOWED_ACTION, "Action not allowed");

        assertThatThrownBy(() -> new FollowUpDecision(due, sampleAction, rejectedGate))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Cannot associate a recommendation outcome with a rejected gate decision");
    }

    @Test
    void cannotAssociateMismatchedOutcomeWithAcceptedGateDecision() {
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE, FollowUpReason.DUE_TODAY);
        ActionGateDecision.Accepted acceptedGate = new ActionGateDecision.Accepted(sampleAction);

        ActionRecommendation differentAction = new ActionRecommendation(
                SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP,
                "Different rationale",
                RecommendationConfidence.of(0.7),
                DraftVariables.empty());

        assertThatThrownBy(() -> new FollowUpDecision(due, differentAction, acceptedGate))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Recommendation outcome must match the gate-approved outcome");
    }

    @Test
    void rejectsActionForIneligibleEvaluationThroughFactoryAndConstructor() {
        FollowUpEvaluation evaluation = evaluation(FollowUpStatus.NOT_YET_DUE, FollowUpReason.CADENCE_NOT_DUE);
        ActionGateDecision.Accepted acceptedGate = new ActionGateDecision.Accepted(sampleAction);

        assertThatThrownBy(() -> FollowUpDecision.accepted(evaluation, acceptedGate))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Cannot associate an action recommendation with a deterministically ineligible customer");
        assertThatThrownBy(() -> new FollowUpDecision(evaluation, sampleAction, acceptedGate))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Cannot associate an action recommendation with a deterministically ineligible customer");
    }

    @Test
    void rejectsNullEvaluationAndMissingFactoryRecommendations() {
        ActionGateDecision.Accepted acceptedGate = new ActionGateDecision.Accepted(sampleAction);
        assertThatThrownBy(() -> new FollowUpDecision(null, sampleAction, acceptedGate))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Evaluation is required");
        assertThatThrownBy(() -> FollowUpDecision.ineligible(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Evaluation is required");

        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE, FollowUpReason.DUE_TODAY);
        assertThatThrownBy(() -> FollowUpDecision.accepted(due, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Gate decision is required");
        assertThatThrownBy(() -> FollowUpDecision.rejected(due, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Gate decision is required");
    }

    @Test
    void ineligibleFactoryReturnsEvaluationWithoutAdvice() {
        FollowUpEvaluation evaluation = evaluation(FollowUpStatus.INELIGIBLE, FollowUpReason.DO_NOT_CONTACT);

        FollowUpDecision decision = FollowUpDecision.ineligible(evaluation);

        assertThat(decision.evaluation()).isSameAs(evaluation);
        assertThat(decision.evaluation().eligible()).isFalse();
        assertThat(decision.hasActionRecommendation()).isFalse();
        assertThat(decision.advisoryRecommendation()).isEmpty();
        assertThat(decision.gateDecision().isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE);

        assertThatThrownBy(() -> FollowUpDecision.ineligible(
                evaluation(FollowUpStatus.DUE, FollowUpReason.DUE_TODAY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer is deterministically eligible");
    }

    @Test
    void gatedRefusalKeepsRefusalWithAcceptedGateDecision() {
        FollowUpEvaluation due = evaluation(FollowUpStatus.OVERDUE, FollowUpReason.OVERDUE);
        FollowUpEvaluation ineligible = evaluation(FollowUpStatus.INELIGIBLE, FollowUpReason.DO_NOT_CONTACT);
        ActionGateDecision.Accepted acceptedRefusal = new ActionGateDecision.Accepted(sampleRefusal);

        for (FollowUpEvaluation evaluation : List.of(due, ineligible)) {
            FollowUpDecision decision = FollowUpDecision.accepted(evaluation, acceptedRefusal);
            assertThat(decision.evaluation()).isSameAs(evaluation);
            assertThat(decision.hasActionRecommendation()).isFalse();
            assertThat(decision.advisoryRecommendation()).contains(sampleRefusal);
            assertThat(decision.gateDecision().isAccepted()).isTrue();
        }
    }

    private FollowUpEvaluation evaluation(FollowUpStatus status, FollowUpReason reason) {
        return new FollowUpEvaluation(
                customerId, status, List.of(reason), evaluatedAt, tenantDate, zoneId,
                status == FollowUpStatus.INELIGIBLE ? null : tenantDate,
                status == FollowUpStatus.INELIGIBLE ? FollowUpTimingSource.NONE : FollowUpTimingSource.LAST_PURCHASE,
                status == FollowUpStatus.INELIGIBLE ? 0 : 30,
                status == FollowUpStatus.INELIGIBLE ? null : evaluatedAt.minusSeconds(30L * 86400));
    }
}
