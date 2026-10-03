package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiDraftRequest;
import io.github.stevdrey.dokene.ai.application.AiDraftResponse;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.application.TrustedBusinessFacts;
import io.github.stevdrey.dokene.ai.domain.DraftOutcome;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraftReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowUpDraftServiceTest {
    private final LocalDate tenantDate = LocalDate.of(2026, 9, 28);
    private final Instant lastPurchase = Instant.parse("2026-08-01T12:00:00Z");
    private final PurchaseId purchaseId = new PurchaseId(UUID.randomUUID());
    private final List<PurchaseBaseline> purchases = List.of(new PurchaseBaseline(purchaseId, 0L, lastPurchase));
    private final Duration timeout = Duration.ofSeconds(3);

    private final AiProvider provider = mock();
    private final RecommendationContextAssembler assembler = mock();
    private final AiActionGate gate = mock();
    private final TenantAuthorizationService authorization = mock();
    private final TenantContextProvider contexts = mock();
    private final FollowUpService followUps = mock();
    private final TenantRepository tenants = mock();
    private final FollowUpRecommendationRateLimiter rateLimiter = mock();

    private final TenantId tenantId = new TenantId(UUID.randomUUID());
    private final IdentityId identityId = new IdentityId(UUID.randomUUID());
    private final TenantContext tenantContext = new TenantContext(
            tenantId, identityId, new TenantMembershipId(UUID.randomUUID()),
            TenantRole.OPERATOR, TenantMembershipStatus.ACTIVE);

    private FollowUpDraftService service;

    @BeforeEach
    void setUp() {
        when(provider.defaultTimeout()).thenReturn(Duration.ofSeconds(15));
        when(provider.maxTimeout()).thenReturn(Duration.ofSeconds(30));
        when(contexts.requireCurrent()).thenReturn(tenantContext);
        when(tenants.findById(tenantId)).thenReturn(Optional.of(Tenant.create(tenantId, "Tienda Café", Instant.now())));
        service = new FollowUpDraftService(provider, assembler, gate, authorization, contexts, followUps, tenants, rateLimiter);
    }

    @Test
    void draftSafe_available_returnsMessageDraft() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        FollowUpEvaluation evaluation = dueEvaluation(customerId);
        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                evaluation, validContext(), purchases, 1L);

        when(followUps.customerPolicy(customerId)).thenReturn(new CustomerFollowUpPolicy(
                tenantId, customerId, 30, null, null, null, 1L));
        when(assembler.assemble(customerId)).thenReturn(assembly);

        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Ana, te saludamos de Tienda Café. Esperamos que disfrutes tu Café Tostado.",
                DraftVariables.of(Map.of("customer", "Ana")),
                "es-419",
                List.of("Compra reciente: Café Tostado"),
                List.of(),
                "Follow-up for repeat purchase item",
                RecommendationConfidence.of(0.85));

        when(provider.draft(any())).thenReturn(new AiDraftResponse(draft, mockMetadata()));
        when(gate.evaluateDraft(eq(customerId), eq(assembly), eq(draft), any(), any(), any()))
                .thenReturn(DraftGateDecision.accepted(draft, evaluation, 1L));

        FollowUpDraftResult result = service.draftSafe(
                customerId,
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                timeout,
                1L);

        assertThat(result.status()).isEqualTo(DraftStatus.AVAILABLE);
        assertThat(result.draft()).isEqualTo(draft);
        assertThat(result.policyVersion()).isEqualTo(1L);
        assertThat(result.draft().locale()).isEqualTo("es-419");
        assertThat(result.draft().evidence()).containsExactly("Compra reciente: Café Tostado");

        verify(authorization).requirePermission(TenantPermission.MESSAGE_DRAFT);
        verify(authorization).requirePermission(TenantPermission.FOLLOWUP_EVALUATE);
        verify(rateLimiter).acquire(tenantId, identityId);
    }

    @Test
    void draftSafe_revalidatesAuthorizationBeforeCallingProvider() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        FollowUpEvaluation evaluation = dueEvaluation(customerId);
        when(followUps.customerPolicy(customerId)).thenReturn(new CustomerFollowUpPolicy(
                tenantId, customerId, 30, null, null, null, 1L));
        when(assembler.assemble(customerId)).thenReturn(new RecommendationContextAssembler.Assembly(
                evaluation, validContext(), purchases, 1L));
        org.mockito.Mockito.doThrow(new TenantAccessDeniedException("Membership revoked"))
                .when(gate).revalidateDraftAuthorization(customerId);

        assertThatThrownBy(() -> service.draftSafe(customerId, null, null, timeout, null))
                .isInstanceOf(TenantAccessDeniedException.class);

        verify(provider, never()).draft(any());
        verify(rateLimiter, never()).acquire(any(), any());
    }

    @Test
    void draftSafe_explicitRepeatPurchaseWithoutPurchaseHistoryIsDisallowed() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        FollowUpEvaluation evaluation = new FollowUpEvaluation(
                customerId, FollowUpStatus.DUE,
                List.of(FollowUpReason.DUE_TODAY), Instant.now(), tenantDate,
                ZoneId.of("UTC"), tenantDate, FollowUpTimingSource.EXPLICIT_DATE, 30, null);
        when(followUps.customerPolicy(customerId)).thenReturn(new CustomerFollowUpPolicy(
                tenantId, customerId, 30, null, null, null, 1L));
        var noPurchaseContext = new RecommendationContext(
                new RecommendationContext.TrustedFacts(
                        tenantDate, "DUE", List.of(TrustedFollowUpReason.DUE_TODAY),
                        30, tenantDate, true, List.of(), Arrays.asList(SemanticAction.values())),
                new RecommendationContext.UntrustedText("Ana", "Cliente habitual", List.of()));
        when(assembler.assemble(customerId)).thenReturn(new RecommendationContextAssembler.Assembly(
                evaluation, noPurchaseContext, List.of(), 1L));

        FollowUpDraftResult result = service.draftSafe(
                customerId, SemanticAction.REPEAT_PURCHASE_FOLLOW_UP, null, timeout, null);

        assertThat(result.status()).isEqualTo(DraftStatus.INELIGIBLE);
        verify(provider, never()).draft(any());
    }

    @Test
    void draftSafe_requiresMessageDraftPermission() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        org.mockito.Mockito.doThrow(new TenantAccessDeniedException("Permission denied"))
                .when(authorization).requirePermission(TenantPermission.MESSAGE_DRAFT);

        assertThatThrownBy(() -> service.draftSafe(customerId, null, null, timeout, null))
                .isInstanceOf(TenantAccessDeniedException.class);

        verify(provider, never()).draft(any());
    }

    @Test
    void draftSafe_optimisticVersionMismatch_throwsConflict() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        when(followUps.customerPolicy(customerId)).thenReturn(new CustomerFollowUpPolicy(
                tenantId, customerId, 30, null, null, null, 2L));

        assertThatThrownBy(() -> service.draftSafe(customerId, null, null, timeout, 1L))
                .isInstanceOf(FollowUpConflictException.class);

        verify(provider, never()).draft(any());
    }

    @Test
    void draftSafe_ineligibleCustomer_returnsIneligibleWithoutCallingProvider() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        FollowUpEvaluation ineligible = new FollowUpEvaluation(
                customerId, FollowUpStatus.INELIGIBLE,
                List.of(FollowUpReason.DO_NOT_CONTACT), Instant.now(), tenantDate,
                ZoneId.of("UTC"), null, FollowUpTimingSource.LAST_PURCHASE, 30, lastPurchase);

        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                ineligible, null, List.of(), 1L);

        when(followUps.customerPolicy(customerId)).thenReturn(new CustomerFollowUpPolicy(
                tenantId, customerId, 30, null, null, null, 1L));
        when(assembler.assemble(customerId)).thenReturn(assembly);

        FollowUpDraftResult result = service.draftSafe(customerId, null, null, timeout, null);

        assertThat(result.status()).isEqualTo(DraftStatus.INELIGIBLE);
        assertThat(result.rejectionReason()).isEqualTo(ActionGateRejectionReason.DO_NOT_CONTACT);
        verify(gate).revalidateDraftAuthorization(customerId);
        verify(provider, never()).draft(any());
    }

    @Test
    void draftSafe_modelRefusal_returnsNoDraftStatus() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        FollowUpEvaluation evaluation = dueEvaluation(customerId);
        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                evaluation, validContext(), purchases, 1L);

        when(followUps.customerPolicy(customerId)).thenReturn(new CustomerFollowUpPolicy(
                tenantId, customerId, 30, null, null, null, 1L));
        when(assembler.assemble(customerId)).thenReturn(assembly);

        NoDraft refusal = new NoDraft(
                NoDraftReason.SAFETY_VIOLATION,
                "Prompt injection attempted in notes",
                RecommendationConfidence.of(0.95));

        when(provider.draft(any())).thenReturn(new AiDraftResponse(refusal, mockMetadata()));
        when(gate.evaluateDraft(eq(customerId), eq(assembly), eq(refusal), any(), any(), any()))
                .thenReturn(DraftGateDecision.accepted(refusal, evaluation, 1L));

        FollowUpDraftResult result = service.draftSafe(customerId, null, null, timeout, null);

        assertThat(result.status()).isEqualTo(DraftStatus.NO_DRAFT);
        assertThat(result.refusal()).isEqualTo(refusal);
        assertThat(result.refusalReason()).isEqualTo(NoDraftReason.SAFETY_VIOLATION);
    }

    @Test
    void draftSafe_gateRejectsHallucinatedOffer_mapsToAiUnavailable() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        FollowUpEvaluation evaluation = dueEvaluation(customerId);
        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                evaluation, validContext(), purchases, 1L);

        when(followUps.customerPolicy(customerId)).thenReturn(new CustomerFollowUpPolicy(
                tenantId, customerId, 30, null, null, null, 1L));
        when(assembler.assemble(customerId)).thenReturn(assembly);

        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Ana, obtén 50% de descuento en tu café.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up with discount",
                RecommendationConfidence.of(0.8));

        when(provider.draft(any())).thenReturn(new AiDraftResponse(draft, mockMetadata()));
        when(gate.evaluateDraft(eq(customerId), eq(assembly), eq(draft), any(), any(), any()))
                .thenReturn(DraftGateDecision.rejected(
                        ActionGateRejectionReason.INVALID_RECOMMENDATION,
                        "Hallucinated discount term '50%'",
                        evaluation, draft, 1L));

        FollowUpDraftResult result = service.draftSafe(customerId, null, null, timeout, null);

        assertThat(result.status()).isEqualTo(DraftStatus.AI_UNAVAILABLE);
        assertThat(result.rejectionReason()).isEqualTo(ActionGateRejectionReason.INVALID_RECOMMENDATION);
    }

    @Test
    void draftSafe_staleState_returnsStaleStateResult() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        FollowUpEvaluation evaluation = dueEvaluation(customerId);
        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                evaluation, validContext(), purchases, 1L);

        when(followUps.customerPolicy(customerId)).thenReturn(new CustomerFollowUpPolicy(
                tenantId, customerId, 30, null, null, null, 1L));
        when(assembler.assemble(customerId)).thenReturn(assembly);

        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Ana, gracias por tu compra.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.8));

        when(provider.draft(any())).thenReturn(new AiDraftResponse(draft, mockMetadata()));
        when(gate.evaluateDraft(eq(customerId), eq(assembly), eq(draft), any(), any(), any()))
                .thenReturn(DraftGateDecision.rejected(
                        ActionGateRejectionReason.STALE_STATE,
                        "State changed during drafting",
                        evaluation, draft, 1L));

        FollowUpDraftResult result = service.draftSafe(customerId, null, null, timeout, null);

        assertThat(result.status()).isEqualTo(DraftStatus.STALE_STATE);
        assertThat(result.rejectionReason()).isEqualTo(ActionGateRejectionReason.STALE_STATE);
    }

    @Test
    void draftSafe_providerException_mapsToAiUnavailable() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        FollowUpEvaluation evaluation = dueEvaluation(customerId);
        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                evaluation, validContext(), purchases, 1L);

        when(followUps.customerPolicy(customerId)).thenReturn(new CustomerFollowUpPolicy(
                tenantId, customerId, 30, null, null, null, 1L));
        when(assembler.assemble(customerId)).thenReturn(assembly);
        when(followUps.evaluateSnapshot(customerId))
                .thenReturn(new FollowUpService.FollowUpEvaluationSnapshot(evaluation, 1L));

        when(provider.draft(any())).thenThrow(new AiProviderException(
                AiFailureCategory.UNAVAILABLE,
                mockMetadata(io.github.stevdrey.dokene.ai.application.AiCompletionStatus.FAILED)));

        FollowUpDraftResult result = service.draftSafe(customerId, null, null, timeout, null);

        assertThat(result.status()).isEqualTo(DraftStatus.AI_UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.UNAVAILABLE);
        verify(gate, org.mockito.Mockito.times(2)).revalidateDraftAuthorization(customerId);
    }

    @Test
    void draftSafe_failedStatusMetadata_mapsToAiUnavailable() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        FollowUpEvaluation evaluation = dueEvaluation(customerId);
        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                evaluation, validContext(), purchases, 1L);

        when(followUps.customerPolicy(customerId)).thenReturn(new CustomerFollowUpPolicy(
                tenantId, customerId, 30, null, null, null, 1L));
        when(assembler.assemble(customerId)).thenReturn(assembly);
        when(followUps.evaluateSnapshot(customerId))
                .thenReturn(new FollowUpService.FollowUpEvaluationSnapshot(evaluation, 1L));

        AiDraftResponse failedResponse = mock(AiDraftResponse.class);
        when(failedResponse.metadata()).thenReturn(mockMetadata(AiCompletionStatus.FAILED));
        when(provider.draft(any())).thenReturn(failedResponse);

        FollowUpDraftResult result = service.draftSafe(customerId, null, null, timeout, null);

        assertThat(result.status()).isEqualTo(DraftStatus.AI_UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.UNAVAILABLE);
        verify(gate, org.mockito.Mockito.times(2)).revalidateDraftAuthorization(customerId);
    }

    @Test
    void draftSafe_unsupportedOperationException_mapsToAiUnavailable() {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        FollowUpEvaluation evaluation = dueEvaluation(customerId);
        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                evaluation, validContext(), purchases, 1L);

        when(followUps.customerPolicy(customerId)).thenReturn(new CustomerFollowUpPolicy(
                tenantId, customerId, 30, null, null, null, 1L));
        when(assembler.assemble(customerId)).thenReturn(assembly);
        when(followUps.evaluateSnapshot(customerId))
                .thenReturn(new FollowUpService.FollowUpEvaluationSnapshot(evaluation, 1L));

        when(provider.draft(any())).thenThrow(new UnsupportedOperationException("Provider does not support drafting"));

        FollowUpDraftResult result = service.draftSafe(customerId, null, null, timeout, null);

        assertThat(result.status()).isEqualTo(DraftStatus.AI_UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.NOT_AVAILABLE);
        verify(gate, org.mockito.Mockito.times(2)).revalidateDraftAuthorization(customerId);
    }

    private FollowUpEvaluation dueEvaluation(CustomerId customerId) {
        return new FollowUpEvaluation(
                customerId, FollowUpStatus.DUE,
                List.of(FollowUpReason.DUE_TODAY), Instant.now(), tenantDate,
                ZoneId.of("UTC"), tenantDate, FollowUpTimingSource.LAST_PURCHASE, 30, lastPurchase);
    }

    private RecommendationContext validContext() {
        var trusted = new RecommendationContext.TrustedFacts(
                tenantDate, "DUE", List.of(TrustedFollowUpReason.DUE_TODAY),
                30, tenantDate, true, List.of(lastPurchase),
                Arrays.asList(SemanticAction.values()));
        var untrusted = new RecommendationContext.UntrustedText("Ana", "Cliente habitual", List.of("Café Tostado"));
        return new RecommendationContext(trusted, untrusted);
    }

    private AiInvocationMetadata mockMetadata() {
        return mockMetadata(io.github.stevdrey.dokene.ai.application.AiCompletionStatus.SUCCEEDED);
    }

    private AiInvocationMetadata mockMetadata(io.github.stevdrey.dokene.ai.application.AiCompletionStatus status) {
        return new AiInvocationMetadata("test", "test-model", "req-1", Duration.ofMillis(10), null, status);
    }
}
