package io.github.stevdrey.dokene.recommendation.domain;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

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
            DraftVariables.empty()
    );

    private final NoRecommendation sampleRefusal = new NoRecommendation(
            NoRecommendationReason.INSUFFICIENT_HISTORY,
            "History too sparse",
            RecommendationConfidence.of(0.95)
    );

    @Test
    void recommendedDecisionPreservesDeterministicFactsAndAdvisoryAction() {
        FollowUpEvaluation dueEvaluation = new FollowUpEvaluation(
                customerId,
                FollowUpStatus.DUE,
                List.of(FollowUpReason.DUE_TODAY),
                evaluatedAt,
                tenantDate,
                zoneId,
                tenantDate,
                FollowUpTimingSource.LAST_PURCHASE,
                30,
                evaluatedAt.minusSeconds(30 * 86400)
        );

        FollowUpDecision decision = FollowUpDecision.recommended(dueEvaluation, sampleAction);

        assertThat(decision.eligible()).isTrue();
        assertThat(decision.customerId()).isEqualTo(customerId);
        assertThat(decision.followUpStatus()).isEqualTo(FollowUpStatus.DUE);
        assertThat(decision.eligibilityReasons()).containsExactly(FollowUpReason.DUE_TODAY);
        assertThat(decision.tenantDate()).isEqualTo(tenantDate);
        assertThat(decision.tenantZone()).isEqualTo(zoneId);
        assertThat(decision.evaluatedAt()).isEqualTo(evaluatedAt);
        assertThat(decision.hasActionRecommendation()).isTrue();
        assertThat(decision.advisoryRecommendation()).contains(sampleAction);
    }

    @Test
    void rejectsActionRecommendationWhenCustomerIsNotEligible() {
        FollowUpEvaluation notDueEvaluation = new FollowUpEvaluation(
                customerId,
                FollowUpStatus.NOT_YET_DUE,
                List.of(FollowUpReason.CADENCE_NOT_DUE),
                evaluatedAt,
                tenantDate,
                zoneId,
                tenantDate.plusDays(10),
                FollowUpTimingSource.LAST_PURCHASE,
                30,
                evaluatedAt
        );

        assertThatThrownBy(() -> FollowUpDecision.recommended(notDueEvaluation, sampleAction))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Cannot recommend action for deterministically ineligible customer");

        // Direct constructor invocation invariant check
        assertThatThrownBy(() -> new FollowUpDecision(
                customerId,
                false,
                FollowUpStatus.NOT_YET_DUE,
                List.of(FollowUpReason.CADENCE_NOT_DUE),
                tenantDate,
                zoneId,
                evaluatedAt,
                tenantDate.plusDays(10),
                FollowUpTimingSource.LAST_PURCHASE,
                30,
                evaluatedAt,
                sampleAction
        )).isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Cannot associate an action recommendation with a deterministically ineligible customer");
    }

    @Test
    void ineligibleFactoryProducesDecisionWithoutRecommendation() {
        FollowUpEvaluation ineligibleEvaluation = new FollowUpEvaluation(
                customerId,
                FollowUpStatus.INELIGIBLE,
                List.of(FollowUpReason.DO_NOT_CONTACT),
                evaluatedAt,
                tenantDate,
                zoneId,
                null,
                FollowUpTimingSource.NONE,
                0,
                null
        );

        FollowUpDecision decision = FollowUpDecision.ineligible(ineligibleEvaluation);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.followUpStatus()).isEqualTo(FollowUpStatus.INELIGIBLE);
        assertThat(decision.eligibilityReasons()).containsExactly(FollowUpReason.DO_NOT_CONTACT);
        assertThat(decision.hasActionRecommendation()).isFalse();
        assertThat(decision.advisoryRecommendation()).isEmpty();
    }

    @Test
    void ineligibleFactoryRejectsEligibleEvaluation() {
        FollowUpEvaluation dueEvaluation = new FollowUpEvaluation(
                customerId,
                FollowUpStatus.DUE,
                List.of(FollowUpReason.DUE_TODAY),
                evaluatedAt,
                tenantDate,
                zoneId,
                tenantDate,
                FollowUpTimingSource.LAST_PURCHASE,
                30,
                null
        );

        assertThatThrownBy(() -> FollowUpDecision.ineligible(dueEvaluation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer is deterministically eligible");
    }

    @Test
    void noRecommendationDecisionCapturesExplicitRefusal() {
        FollowUpEvaluation dueEvaluation = new FollowUpEvaluation(
                customerId,
                FollowUpStatus.OVERDUE,
                List.of(FollowUpReason.OVERDUE),
                evaluatedAt,
                tenantDate,
                zoneId,
                tenantDate.minusDays(5),
                FollowUpTimingSource.LAST_PURCHASE,
                30,
                null
        );

        FollowUpDecision decision = FollowUpDecision.noRecommendation(dueEvaluation, sampleRefusal);

        assertThat(decision.eligible()).isTrue();
        assertThat(decision.hasActionRecommendation()).isFalse();
        assertThat(decision.advisoryRecommendation()).contains(sampleRefusal);
    }
}
