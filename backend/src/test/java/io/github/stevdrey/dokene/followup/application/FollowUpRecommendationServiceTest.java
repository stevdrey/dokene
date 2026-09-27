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
import io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import static org.mockito.Mockito.verify;

class FollowUpRecommendationServiceTest {
    private final LocalDate tenantDate = LocalDate.of(2026, 9, 25);
    private final Instant lastPurchase = Instant.parse("2026-08-01T12:00:00Z");
    private final PurchaseId purchaseId = new PurchaseId(UUID.randomUUID());
    private final List<PurchaseBaseline> purchases = List.of(new PurchaseBaseline(purchaseId, 0L, lastPurchase));
    private final Duration timeout = Duration.ofSeconds(3);
    private final RecommendationContextAssembler assembler = mock();
    private final AiActionGate gate = mock();
    private final TenantAuthorizationService authorization = mock();
    private final TenantContextProvider contexts = mock();
    private final FollowUpService followUps = mock();

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
            var assembly = new RecommendationContextAssembler.Assembly(due, context(), purchases);
            when(assembler.assemble(due.customerId())).thenReturn(assembly);
            when(gate.evaluate(due.customerId(), assembly, outcome)).thenReturn(ActionGateDecision.accepted(outcome, due));

            FollowUpDecision decision = new FollowUpRecommendationService(fake, assembler, gate).recommend(due.customerId(), timeout);
            assertThat(decision.evaluation()).isSameAs(due);
            assertThat(decision.advisoryRecommendation()).contains(outcome);
            assertThat(decision.gateDecision().isAccepted()).isTrue();
            assertThat(fake.lastRequest().operation()).isEqualTo(AiOperation.NEXT_BEST_ACTION);
            assertThat(fake.lastRequest().context().trusted().tenantDate()).isEqualTo(tenantDate);
            assertThat(fake.lastRequest().context().trusted().effectiveCadenceDays()).isEqualTo(30);
            assertThat(fake.lastRequest().context().trusted().purchaseDates()).containsExactly(lastPurchase);
            assertThat(fake.lastRequest().timeout()).isEqualTo(timeout);

            FollowUpDecision directDecision = new FollowUpRecommendationService(fake, assembler, gate).recommend(due, timeout);
            assertThat(directDecision.evaluation()).isSameAs(due);
            assertThat(directDecision.advisoryRecommendation()).contains(outcome);
        }
    }

    @Test
    void ineligibleEvaluationNeverCallsProviderOrGate() {
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.failure(AiFailureCategory.UNAVAILABLE);
        FollowUpEvaluation ineligible = evaluation(FollowUpStatus.INELIGIBLE);

        when(assembler.assemble(ineligible.customerId())).thenReturn(new RecommendationContextAssembler.Assembly(ineligible, null, List.of()));
        FollowUpDecision decision = new FollowUpRecommendationService(fake, assembler, gate).recommend(ineligible.customerId(), timeout);

        assertThat(decision.evaluation()).isSameAs(ineligible);
        assertThat(decision.advisoryRecommendation()).isEmpty();
        assertThat(fake.invocationCount()).isZero();
        verifyNoInteractions(gate);

        FollowUpDecision directDecision = new FollowUpRecommendationService(fake, assembler, gate).recommend(ineligible, timeout);
        assertThat(directDecision.evaluation()).isSameAs(ineligible);
        assertThat(directDecision.advisoryRecommendation()).isEmpty();
        assertThat(fake.invocationCount()).isZero();
        verifyNoInteractions(gate);
    }

    @Test
    void malformedOutputAndProviderFailurePropagateWithoutFallbackAction() {
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE);
        for (DeterministicFakeAiProvider fake : List.of(
                DeterministicFakeAiProvider.malformedOutput(),
                DeterministicFakeAiProvider.failure(AiFailureCategory.TIMEOUT))) {
            when(assembler.assemble(due.customerId())).thenReturn(new RecommendationContextAssembler.Assembly(due, context(), purchases));
            assertThatThrownBy(() -> new FollowUpRecommendationService(fake, assembler, gate).recommend(due.customerId(), timeout))
                    .isInstanceOf(AiProviderException.class);
        }
    }

    @Test
    void callerSuppliedEvaluationDelegatesToCustomerIdAndUsesAuthoritativeOutcome() {
        ActionRecommendation action = new ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Purchase cadence reached",
                RecommendationConfidence.of(0.8), DraftVariables.empty());
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.success(action);
        FollowUpEvaluation forged = evaluation(FollowUpStatus.DUE);
        FollowUpEvaluation authoritative = evaluation(FollowUpStatus.DUE);
        var assembly = new RecommendationContextAssembler.Assembly(authoritative, context(), purchases);

        when(assembler.assemble(forged.customerId())).thenReturn(assembly);
        when(gate.evaluate(forged.customerId(), assembly, action)).thenReturn(ActionGateDecision.accepted(action, authoritative));

        FollowUpDecision directDecision = new FollowUpRecommendationService(fake, assembler, gate).recommend(forged, timeout);
        assertThat(directDecision.evaluation()).isSameAs(authoritative);
        assertThat(directDecision.advisoryRecommendation()).contains(action);
    }

    @Test
    void callerSuppliedEligibleEvaluationShortCircuitsWhenAuthoritativeEvaluationIsIneligible() {
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.failure(AiFailureCategory.UNAVAILABLE);
        FollowUpEvaluation forgedEligible = evaluation(FollowUpStatus.DUE);
        FollowUpEvaluation authoritativeIneligible = evaluation(FollowUpStatus.INELIGIBLE);

        when(assembler.assemble(forgedEligible.customerId())).thenReturn(new RecommendationContextAssembler.Assembly(authoritativeIneligible, null, List.of()));

        FollowUpDecision directDecision = new FollowUpRecommendationService(fake, assembler, gate).recommend(forgedEligible, timeout);
        assertThat(directDecision.evaluation()).isSameAs(authoritativeIneligible);
        assertThat(directDecision.advisoryRecommendation()).isEmpty();
        assertThat(fake.invocationCount()).isZero();
        verifyNoInteractions(gate);
    }

    @Test
    void gateRejectionReturnsRejectedDecisionWithTypedReason() {
        ActionRecommendation action = new ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Purchase cadence reached",
                RecommendationConfidence.of(0.8), DraftVariables.empty());
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.success(action);
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE);
        var assembly = new RecommendationContextAssembler.Assembly(due, context(), purchases);

        when(assembler.assemble(due.customerId())).thenReturn(assembly);
        when(gate.evaluate(due.customerId(), assembly, action)).thenReturn(
                ActionGateDecision.rejected(ActionGateRejectionReason.STALE_STATE, "State changed"));

        FollowUpDecision decision = new FollowUpRecommendationService(fake, assembler, gate).recommend(due.customerId(), timeout);

        assertThat(decision.evaluation()).isSameAs(due);
        assertThat(decision.hasActionRecommendation()).isFalse();
        assertThat(decision.advisoryRecommendation()).isEmpty();
        assertThat(decision.gateDecision().isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.STALE_STATE);
    }

    @Test
    void gateRejectionReturnsFreshEvaluationWhenGateDetectsStateChange() {
        ActionRecommendation action = new ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Purchase cadence reached",
                RecommendationConfidence.of(0.8), DraftVariables.empty());
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.success(action);
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE);
        FollowUpEvaluation freshIneligible = evaluation(FollowUpStatus.NOT_YET_DUE);
        var assembly = new RecommendationContextAssembler.Assembly(due, context(), purchases);

        when(assembler.assemble(due.customerId())).thenReturn(assembly);
        when(gate.evaluate(due.customerId(), assembly, action)).thenReturn(
                ActionGateDecision.rejected(ActionGateRejectionReason.STALE_STATE, "Purchase added", freshIneligible));

        FollowUpDecision decision = new FollowUpRecommendationService(fake, assembler, gate).recommend(due.customerId(), timeout);

        assertThat(decision.evaluation()).isSameAs(freshIneligible);
        assertThat(decision.hasActionRecommendation()).isFalse();
        assertThat(decision.advisoryRecommendation()).isEmpty();
        assertThat(decision.gateDecision().isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.STALE_STATE);
    }

    @Test
    void serviceUsesGateApprovedOutcomeWhenGateNormalizesOrSubstitutes() {
        ActionRecommendation rawAction = new ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Raw model rationale",
                RecommendationConfidence.of(0.8), DraftVariables.empty());
        ActionRecommendation normalizedAction = new ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Approved safe rationale",
                RecommendationConfidence.of(0.8), DraftVariables.empty());

        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.success(rawAction);
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE);
        var assembly = new RecommendationContextAssembler.Assembly(due, context(), purchases);

        when(assembler.assemble(due.customerId())).thenReturn(assembly);
        when(gate.evaluate(due.customerId(), assembly, rawAction)).thenReturn(ActionGateDecision.accepted(normalizedAction, due));

        FollowUpDecision decision = new FollowUpRecommendationService(fake, assembler, gate).recommend(due.customerId(), timeout);

        assertThat(decision.evaluation()).isSameAs(due);
        assertThat(decision.advisoryRecommendation()).contains(normalizedAction);
        assertThat(decision.advisoryRecommendation()).get().isNotEqualTo(rawAction);
    }

    @Test
    void gateRejectionReturnsFreshEvaluationWhenCustomerLosesConsentInFlight() {
        ActionRecommendation action = new ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Purchase cadence reached",
                RecommendationConfidence.of(0.8), DraftVariables.empty());
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.success(action);
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE);
        FollowUpEvaluation freshIneligible = evaluation(FollowUpStatus.INELIGIBLE);
        var assembly = new RecommendationContextAssembler.Assembly(due, context(), purchases);

        when(assembler.assemble(due.customerId())).thenReturn(assembly);
        when(gate.evaluate(due.customerId(), assembly, action)).thenReturn(
                ActionGateDecision.rejected(ActionGateRejectionReason.NO_CONTACT_CONSENT, "Consent revoked", freshIneligible));

        FollowUpDecision decision = new FollowUpRecommendationService(fake, assembler, gate).recommend(due.customerId(), timeout);

        assertThat(decision.evaluation()).isSameAs(freshIneligible);
        assertThat(decision.evaluation().eligible()).isFalse();
        assertThat(decision.hasActionRecommendation()).isFalse();
        assertThat(decision.advisoryRecommendation()).isEmpty();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.NO_CONTACT_CONSENT);
    }

    @Test
    void recommendSafeReturnsAvailableWhenActionAccepted() {
        ActionRecommendation action = new ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Purchase cadence reached",
                RecommendationConfidence.of(0.8), DraftVariables.empty());
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.success(action);
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE);
        var assembly = new RecommendationContextAssembler.Assembly(due, context(), purchases);

        when(assembler.assemble(due.customerId())).thenReturn(assembly);
        when(gate.evaluate(due.customerId(), assembly, action)).thenReturn(ActionGateDecision.accepted(action, due));
        when(followUps.customerPolicy(due.customerId()))
                .thenReturn(new CustomerFollowUpPolicy(TenantId.random(), due.customerId(), 30, null, null, null, 2L));

        FollowUpRecommendationService service = new FollowUpRecommendationService(
                fake, assembler, gate, authorization, contexts, followUps);

        FollowUpRecommendationResult result = service.recommendSafe(due.customerId(), timeout, 2L);

        assertThat(result.status()).isEqualTo(RecommendationStatus.AVAILABLE);
        assertThat(result.evaluation()).isSameAs(due);
        assertThat(result.advisoryRecommendation()).contains(action);
        assertThat(result.policyVersion()).isEqualTo(2L);
        assertThat(result.explicitRefusal()).isEmpty();
        assertThat(result.providerFailure()).isEmpty();
        verify(authorization).requirePermission(TenantPermission.FOLLOWUP_EVALUATE);
    }

    @Test
    void recommendSafeReturnsRefusalWhenNoRecommendationAccepted() {
        NoRecommendation refusal = new NoRecommendation(NoRecommendationReason.UNCERTAIN_INTENT,
                "Insufficient signal", RecommendationConfidence.of(0.4));
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.success(refusal);
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE);
        var assembly = new RecommendationContextAssembler.Assembly(due, context(), purchases);

        when(assembler.assemble(due.customerId())).thenReturn(assembly);
        when(gate.evaluate(due.customerId(), assembly, refusal)).thenReturn(ActionGateDecision.accepted(refusal, due));
        when(followUps.customerPolicy(due.customerId()))
                .thenReturn(new CustomerFollowUpPolicy(TenantId.random(), due.customerId(), 30, null, null, null, 1L));

        FollowUpRecommendationService service = new FollowUpRecommendationService(
                fake, assembler, gate, authorization, contexts, followUps);

        FollowUpRecommendationResult result = service.recommendSafe(due.customerId(), timeout, null);

        assertThat(result.status()).isEqualTo(RecommendationStatus.NO_RECOMMENDATION);
        assertThat(result.evaluation()).isSameAs(due);
        assertThat(result.advisoryRecommendation()).isEmpty();
        assertThat(result.explicitRefusal()).contains(NoRecommendationReason.UNCERTAIN_INTENT);
        assertThat(result.policyVersion()).isEqualTo(1L);
    }

    @Test
    void recommendSafeReturnsIneligibleWhenDeterministicEvaluationIneligible() {
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.failure(AiFailureCategory.UNAVAILABLE);
        FollowUpEvaluation ineligible = evaluation(FollowUpStatus.INELIGIBLE);

        when(assembler.assemble(ineligible.customerId()))
                .thenReturn(new RecommendationContextAssembler.Assembly(ineligible, null, List.of()));
        when(followUps.customerPolicy(ineligible.customerId()))
                .thenReturn(new CustomerFollowUpPolicy(TenantId.random(), ineligible.customerId(), 30, null, null, null, 1L));

        FollowUpRecommendationService service = new FollowUpRecommendationService(
                fake, assembler, gate, authorization, contexts, followUps);

        FollowUpRecommendationResult result = service.recommendSafe(ineligible.customerId(), timeout, 1L);

        assertThat(result.status()).isEqualTo(RecommendationStatus.INELIGIBLE);
        assertThat(result.evaluation()).isSameAs(ineligible);
        assertThat(result.advisoryRecommendation()).isEmpty();
        assertThat(fake.invocationCount()).isZero();
        verifyNoInteractions(gate);
    }

    @Test
    void recommendSafeReturnsStaleStateWhenGateRejectsWithStaleState() {
        ActionRecommendation action = new ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Purchase cadence reached",
                RecommendationConfidence.of(0.8), DraftVariables.empty());
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.success(action);
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE);
        var assembly = new RecommendationContextAssembler.Assembly(due, context(), purchases);

        when(assembler.assemble(due.customerId())).thenReturn(assembly);
        when(gate.evaluate(due.customerId(), assembly, action)).thenReturn(
                ActionGateDecision.rejected(ActionGateRejectionReason.STALE_STATE, "State changed"));
        when(followUps.customerPolicy(due.customerId()))
                .thenReturn(new CustomerFollowUpPolicy(TenantId.random(), due.customerId(), 30, null, null, null, 3L));

        FollowUpRecommendationService service = new FollowUpRecommendationService(
                fake, assembler, gate, authorization, contexts, followUps);

        FollowUpRecommendationResult result = service.recommendSafe(due.customerId(), timeout, 3L);

        assertThat(result.status()).isEqualTo(RecommendationStatus.STALE_STATE);
        assertThat(result.evaluation()).isSameAs(due);
        assertThat(result.advisoryRecommendation()).isEmpty();
        assertThat(result.gateRejection()).contains(ActionGateRejectionReason.STALE_STATE);
    }

    @Test
    void recommendSafeReturnsAiUnavailableOnProviderFailurePreservingEvaluation() {
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE);
        var assembly = new RecommendationContextAssembler.Assembly(due, context(), purchases);

        when(assembler.assemble(due.customerId())).thenReturn(assembly);
        when(followUps.evaluate(due.customerId())).thenReturn(due);
        when(followUps.customerPolicy(due.customerId()))
                .thenReturn(new CustomerFollowUpPolicy(TenantId.random(), due.customerId(), 30, null, null, null, 5L));

        DeterministicFakeAiProvider timeoutFake = DeterministicFakeAiProvider.failure(AiFailureCategory.TIMEOUT);
        FollowUpRecommendationService service = new FollowUpRecommendationService(
                timeoutFake, assembler, gate, authorization, contexts, followUps);

        FollowUpRecommendationResult result = service.recommendSafe(due.customerId(), timeout, 5L);

        assertThat(result.status()).isEqualTo(RecommendationStatus.AI_UNAVAILABLE);
        assertThat(result.evaluation()).isSameAs(due);
        assertThat(result.advisoryRecommendation()).isEmpty();
        assertThat(result.providerFailure()).contains("TIMEOUT");
        assertThat(result.policyVersion()).isEqualTo(5L);
        verifyNoInteractions(gate);
    }

    @Test
    void recommendSafeThrowsConflictWhenExpectedVersionMismatches() {
        FollowUpEvaluation due = evaluation(FollowUpStatus.DUE);
        when(followUps.customerPolicy(due.customerId()))
                .thenReturn(new CustomerFollowUpPolicy(TenantId.random(), due.customerId(), 30, null, null, null, 5L));

        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.failure(AiFailureCategory.UNAVAILABLE);
        FollowUpRecommendationService service = new FollowUpRecommendationService(
                fake, assembler, gate, authorization, contexts, followUps);

        assertThatThrownBy(() -> service.recommendSafe(due.customerId(), timeout, 4L))
                .isInstanceOf(FollowUpConflictException.class);
    }

    private RecommendationContext context() {
        return new RecommendationContext(new RecommendationContext.TrustedFacts(tenantDate, "DUE",
                List.of(TrustedFollowUpReason.DUE_TODAY), 30, tenantDate, true, List.of(lastPurchase),
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
