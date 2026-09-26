package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.DeterministicFakeAiProvider;
import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FollowUpRecommendationServiceTest {
    private final LocalDate tenantDate = LocalDate.of(2026, 9, 25);
    private final Instant lastPurchase = Instant.parse("2026-08-01T12:00:00Z");
    private final Duration timeout = Duration.ofSeconds(3);
    private final RecommendationContextAssembler assembler = mock();

    @Test
    void eligibleEvaluationUsesFakeForActionAndRefusal() {
        ActionRecommendation action = new ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Purchase cadence reached",
                RecommendationConfidence.of(0.8), DraftVariables.empty());
        NoRecommendation refusal = new NoRecommendation(NoRecommendationReason.UNCERTAIN_INTENT,
                "Insufficient signal", RecommendationConfidence.of(0.4));
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE);

        for (var outcome : List.of(action, refusal)) {
            DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.success(outcome);
            when(assembler.assemble(due.customerId())).thenReturn(new RecommendationContextAssembler.Assembly(due, context()));
            when(assembler.assemble(due)).thenReturn(new RecommendationContextAssembler.Assembly(due, context()));

            FollowUpDecision decision = new FollowUpRecommendationService(fake, assembler).recommend(due.customerId(), timeout);
            assertThat(decision.evaluation()).isSameAs(due);
            assertThat(decision.advisoryRecommendation()).contains(outcome);
            assertThat(fake.lastRequest().operation()).isEqualTo(AiOperation.NEXT_BEST_ACTION);
            assertThat(fake.lastRequest().context().trusted().tenantDate()).isEqualTo(tenantDate);
            assertThat(fake.lastRequest().context().trusted().effectiveCadenceDays()).isEqualTo(30);
            assertThat(fake.lastRequest().context().trusted().purchaseDates()).containsExactly(lastPurchase);
            assertThat(fake.lastRequest().timeout()).isEqualTo(timeout);

            FollowUpDecision directDecision = new FollowUpRecommendationService(fake, assembler).recommend(due, timeout);
            assertThat(directDecision.evaluation()).isSameAs(due);
            assertThat(directDecision.advisoryRecommendation()).contains(outcome);
        }
    }

    @Test
    void ineligibleEvaluationNeverCallsProvider() {
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.failure(AiFailureCategory.UNAVAILABLE);
        FollowUpEvaluation ineligible = evaluation(FollowUpStatus.INELIGIBLE);

        when(assembler.assemble(ineligible.customerId())).thenReturn(new RecommendationContextAssembler.Assembly(ineligible, null));
        FollowUpDecision decision = new FollowUpRecommendationService(fake, assembler).recommend(ineligible.customerId(), timeout);

        assertThat(decision.evaluation()).isSameAs(ineligible);
        assertThat(decision.advisoryRecommendation()).isEmpty();
        assertThat(fake.invocationCount()).isZero();

        RecommendationContextAssembler isolatedAssembler = mock();
        FollowUpDecision directDecision = new FollowUpRecommendationService(fake, isolatedAssembler).recommend(ineligible, timeout);
        assertThat(directDecision.evaluation()).isSameAs(ineligible);
        assertThat(directDecision.advisoryRecommendation()).isEmpty();
        verifyNoInteractions(isolatedAssembler);
    }

    @Test
    void malformedOutputAndProviderFailurePropagateWithoutFallbackAction() {
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE);
        for (DeterministicFakeAiProvider fake : List.of(
                DeterministicFakeAiProvider.malformedOutput(),
                DeterministicFakeAiProvider.failure(AiFailureCategory.TIMEOUT))) {
            when(assembler.assemble(due.customerId())).thenReturn(new RecommendationContextAssembler.Assembly(due, context()));
            assertThatThrownBy(() -> new FollowUpRecommendationService(fake, assembler).recommend(due.customerId(), timeout))
                    .isInstanceOf(AiProviderException.class);
        }
    }

    private RecommendationContext context() {
        return new RecommendationContext(new RecommendationContext.TrustedFacts(tenantDate, "DUE",
                List.of("DUE_TODAY"), 30, tenantDate, true, List.of(lastPurchase),
                List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP)),
                new RecommendationContext.UntrustedText("Customer", null, List.of("Purchase")));
    }

    private FollowUpEvaluation evaluation(FollowUpStatus status) {
        boolean eligible = status == FollowUpStatus.DUE;
        return new FollowUpEvaluation(new CustomerId(UUID.fromString("00000000-0000-0000-0000-000000000090")),
                status, List.of(eligible ? FollowUpReason.DUE_TODAY : FollowUpReason.DO_NOT_CONTACT),
                Instant.parse("2026-09-25T12:00:00Z"), tenantDate, ZoneId.of("America/Costa_Rica"),
                eligible ? tenantDate : null,
                eligible ? FollowUpTimingSource.LAST_PURCHASE : FollowUpTimingSource.NONE,
                eligible ? 30 : 0, eligible ? lastPurchase : null);
    }
}
