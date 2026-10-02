package io.github.stevdrey.dokene.followup.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.FollowUpQueuePage;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.FollowUpDismissal;
import io.github.stevdrey.dokene.followup.domain.FollowUpQueueItem;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.followup.application.FollowUpConflictException;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationResult;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationService;
import io.github.stevdrey.dokene.followup.application.RecommendationStatus;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraftReason;
import io.github.stevdrey.dokene.followup.application.DraftStatus;
import io.github.stevdrey.dokene.followup.application.FollowUpDraftResult;
import io.github.stevdrey.dokene.followup.application.FollowUpDraftService;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class FollowUpControllerTest {

    private FollowUpService service;
    private FollowUpRecommendationService recommendations;
    private FollowUpDraftService drafts;
    private MockMvc mvc;
    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock();
        recommendations = mock();
        drafts = mock();
        mvc = MockMvcBuilders.standaloneSetup(new FollowUpController(service, recommendations, drafts))
                .setControllerAdvice(new FollowUpExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"01\"", "\"0001\"", "\"00\"", "\"bad\"", "\"\"", "1", "\"1.0\"", "\"-1\""})
    void configureTenantRejectsNoncanonicalAndMalformedEtags(String invalidEtag) throws Exception {
        mvc.perform(put("/api/follow-up-policy")
                .header("If-Match", invalidEtag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cadenceDays\":14,\"timeZone\":\"UTC\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void configureTenantAcceptsCanonicalEtags() throws Exception {
        TenantId tenantId = TenantId.random();
        when(service.configureTenant(14, ZoneId.of("UTC"), 0))
                .thenReturn(new TenantFollowUpPolicy(tenantId, 14, ZoneId.of("UTC"), 1));

        mvc.perform(put("/api/follow-up-policy")
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cadenceDays\":14,\"timeZone\":\"UTC\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));

        verify(service).configureTenant(14, ZoneId.of("UTC"), 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"01\"", "\"00\"", "\"007\""})
    void configureCustomerRejectsNoncanonicalEtags(String invalidEtag) throws Exception {
        mvc.perform(put("/api/customers/{id}/follow-up-policy", customerId)
                .header("If-Match", invalidEtag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cadenceDays\":7}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void configureCustomerAcceptsCanonicalEtag() throws Exception {
        TenantId tenantId = TenantId.random();
        CustomerId cId = new CustomerId(customerId);
        when(service.configureCustomer(eq(cId), eq(7), eq(null), eq(1L)))
                .thenReturn(new CustomerFollowUpPolicy(tenantId, cId, 7, null, null, null, 2));

        mvc.perform(put("/api/customers/{id}/follow-up-policy", customerId)
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cadenceDays\":7}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"01\"", "\"00\""})
    void snoozeRejectsNoncanonicalEtags(String invalidEtag) throws Exception {
        mvc.perform(put("/api/customers/{id}/follow-up-snooze", customerId)
                .header("If-Match", invalidEtag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"until\":\"2026-09-12\"}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"01\"", "\"00\""})
    void recordManualFollowUpRejectsNoncanonicalEtags(String invalidEtag) throws Exception {
        mvc.perform(post("/api/customers/{id}/manual-follow-ups", customerId)
                .header("If-Match", invalidEtag)
                .header("Idempotency-Key", "valid-key-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordManualFollowUpRejectsOversizedNotesBeforeServiceInvocation() throws Exception {
        mvc.perform(post("/api/customers/{id}/manual-follow-ups", customerId)
                .header("If-Match", "\"0\"")
                .header("Idempotency-Key", "valid-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"notes\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void dismissRejectsOversizedNotesBeforeServiceInvocation() throws Exception {
        mvc.perform(post("/api/customers/{id}/follow-up-dismissals", customerId)
                .header("If-Match", "\"0\"")
                .header("Idempotency-Key", "valid-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"notes\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void dueQueueReturnsPaginatedItemsAndFiltersByStatus() throws Exception {
        var queueItem = new FollowUpQueueItem(new CustomerId(customerId), "Jane Doe", "+50688881234",
                FollowUpStatus.OVERDUE, List.of(FollowUpReason.OVERDUE), LocalDate.of(2026, 9, 1),
                FollowUpTimingSource.LAST_PURCHASE, 2L, 30, Instant.parse("2026-08-01T00:00:00Z"),
                null, null, Instant.parse("2026-09-10T12:00:00Z"));
        when(service.dueQueue(any()))
                .thenReturn(new FollowUpQueuePage(List.of(queueItem), "cursor-token-1"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/follow-up-queue")
                .param("status", "OVERDUE")
                .param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].customerId").value(customerId.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].status").value("OVERDUE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].timingSource").value("LAST_PURCHASE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.nextCursor").value("cursor-token-1"));
    }

    @Test
    void dismissRecordsDismissalWithETagAndNotes() throws Exception {
        TenantId tenantId = TenantId.random();
        CustomerId cId = new CustomerId(customerId);
        var dismissal = new FollowUpDismissal(UUID.randomUUID(), tenantId, cId, LocalDate.of(2026, 9, 10),
                3L, Instant.now(), new IdentityId(UUID.randomUUID()), TenantMembershipId.random(), "customer requested");
        when(service.dismiss(eq(cId), eq(2L), eq("dismiss-key-1"), eq("customer requested")))
                .thenReturn(new io.github.stevdrey.dokene.followup.application.FollowUpDismissalResult(dismissal, true));

        mvc.perform(post("/api/customers/{id}/follow-up-dismissals", customerId)
                .header("If-Match", "\"2\"")
                .header("Idempotency-Key", "dismiss-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"notes\":\"customer requested\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.notes").value("customer requested"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.policyVersion").value(3));
    }

    @Test
    void dismissReplayReturnsOk() throws Exception {
        TenantId tenantId = TenantId.random();
        CustomerId cId = new CustomerId(customerId);
        var dismissal = new FollowUpDismissal(UUID.randomUUID(), tenantId, cId, LocalDate.of(2026, 9, 10),
                3L, Instant.now(), new IdentityId(UUID.randomUUID()), TenantMembershipId.random(), null);
        when(service.dismiss(eq(cId), eq(2L), eq("dismiss-key-1"), eq(null)))
                .thenReturn(new io.github.stevdrey.dokene.followup.application.FollowUpDismissalResult(dismissal, false));

        mvc.perform(post("/api/customers/{id}/follow-up-dismissals", customerId)
                .header("If-Match", "\"2\"")
                .header("Idempotency-Key", "dismiss-key-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""));
    }

    @Test
    void requestRecommendationReturnsAvailableWithEtag() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        ActionRecommendation action = new ActionRecommendation(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Cadence reached",
                RecommendationConfidence.of(0.85),
                DraftVariables.empty());
        var evaluation = testEvaluation(FollowUpStatus.DUE);
        when(recommendations.recommendSafe(eq(cId), any(), eq(2L)))
                .thenReturn(FollowUpRecommendationResult.available(evaluation, action, 2L));

        mvc.perform(post("/api/customers/{id}/recommendation", customerId)
                .header("If-Match", "\"2\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"timeoutMs\":5000}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.recommendation.action").value("REPEAT_PURCHASE_FOLLOW_UP"))
                .andExpect(jsonPath("$.recommendation.rationale").value("Cadence reached"))
                .andExpect(jsonPath("$.evaluation.reasons[0]").value("DUE_TODAY"));
    }

    @Test
    void requestRecommendationReturnsRefusal() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        var evaluation = testEvaluation(FollowUpStatus.DUE);
        when(recommendations.recommendSafe(eq(cId), any(), eq(null)))
                .thenReturn(FollowUpRecommendationResult.refusal(evaluation, NoRecommendationReason.UNCERTAIN_INTENT, 1L));

        mvc.perform(post("/api/customers/{id}/recommendation", customerId))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("NO_RECOMMENDATION"))
                .andExpect(jsonPath("$.refusalReason").value("UNCERTAIN_INTENT"));
    }

    @Test
    void requestRecommendationReturnsAiUnavailableWithEvaluation() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        var evaluation = testEvaluation(FollowUpStatus.DUE);
        when(recommendations.recommendSafe(eq(cId), any(), eq(1L)))
                .thenReturn(FollowUpRecommendationResult.aiUnavailable(evaluation, "TIMEOUT", 1L));

        mvc.perform(post("/api/customers/{id}/recommendation", customerId)
                .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.unavailableReason").value("TIMEOUT"))
                .andExpect(jsonPath("$.evaluation.status").value("DUE"));
    }

    @Test
    void requestRecommendationReturnsIneligible() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        var evaluation = testEvaluation(FollowUpStatus.INELIGIBLE);
        when(recommendations.recommendSafe(eq(cId), any(), eq(null)))
                .thenReturn(FollowUpRecommendationResult.ineligible(evaluation, null, 1L));

        mvc.perform(post("/api/customers/{id}/recommendation", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INELIGIBLE"))
                .andExpect(jsonPath("$.evaluation.status").value("INELIGIBLE"));
    }

    @Test
    void requestRecommendationReturnsStaleState() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        var evaluation = testEvaluation(FollowUpStatus.DUE);
        when(recommendations.recommendSafe(eq(cId), any(), eq(1L)))
                .thenReturn(FollowUpRecommendationResult.staleState(evaluation, 1L));

        mvc.perform(post("/api/customers/{id}/recommendation", customerId)
                .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE_STATE"))
                .andExpect(jsonPath("$.rejectionReason").value("STALE_STATE"));
    }

    @Test
    void requestRecommendationRejectsMalformedEtag() throws Exception {
        mvc.perform(post("/api/customers/{id}/recommendation", customerId)
                .header("If-Match", "\"bad\""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestRecommendationReturnsConflictOnVersionMismatch() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        when(recommendations.recommendSafe(eq(cId), any(), eq(1L)))
                .thenThrow(new FollowUpConflictException());

        mvc.perform(post("/api/customers/{id}/recommendation", customerId)
                .header("If-Match", "\"1\""))
                .andExpect(status().isConflict());
    }

    @Test
    void requestRecommendationSupportsAliasPath() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        var evaluation = testEvaluation(FollowUpStatus.DUE);
        when(recommendations.recommendSafe(eq(cId), any(), eq(null)))
                .thenReturn(FollowUpRecommendationResult.refusal(evaluation, NoRecommendationReason.UNCERTAIN_INTENT, 1L));

        mvc.perform(post("/api/customers/{id}/follow-up-recommendation", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_RECOMMENDATION"));
    }

    @Test
    void requestRecommendationReturnsRefusalWithRationaleAndConfidence() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        var evaluation = testEvaluation(FollowUpStatus.DUE);
        NoRecommendation refusal = new NoRecommendation(NoRecommendationReason.UNCERTAIN_INTENT,
                "Model indicates uncertainty", RecommendationConfidence.of(0.65));
        when(recommendations.recommendSafe(eq(cId), any(), eq(null)))
                .thenReturn(FollowUpRecommendationResult.refusal(evaluation, refusal, 1L));

        mvc.perform(post("/api/customers/{id}/recommendation", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_RECOMMENDATION"))
                .andExpect(jsonPath("$.refusalReason").value("UNCERTAIN_INTENT"))
                .andExpect(jsonPath("$.refusal.reason").value("UNCERTAIN_INTENT"))
                .andExpect(jsonPath("$.refusal.rationale").value("Model indicates uncertainty"))
                .andExpect(jsonPath("$.refusal.confidence").value(0.65));
    }

    @Test
    void requestRecommendationReturnsTooManyRequestsWhenRateLimited() throws Exception {
        when(recommendations.recommendSafe(eq(new CustomerId(customerId)), any(), any()))
                .thenThrow(new io.github.stevdrey.dokene.followup.application.RecommendationRateLimitExceededException(
                        "Rate limit exceeded", java.time.Duration.ofSeconds(5)));

        mvc.perform(post("/api/customers/{id}/recommendation", customerId))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"));
    }

    @Test
    void requestRecommendationRejectsUnauthorizedCallerWithoutInvokingService() throws Exception {
        var authorization = mock(io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService.class);
        org.mockito.Mockito.doThrow(new io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException("Forbidden"))
                .when(authorization).requirePermission(io.github.stevdrey.dokene.tenant.domain.TenantPermission.FOLLOWUP_EVALUATE);

        MockMvc customMvc = MockMvcBuilders.standaloneSetup(new FollowUpController(service, recommendations, null, null, authorization))
                .setControllerAdvice(new FollowUpExceptionHandler()).build();

        customMvc.perform(post("/api/customers/{id}/recommendation", customerId))
                .andExpect(status().isForbidden());

        verify(authorization).requirePermission(io.github.stevdrey.dokene.tenant.domain.TenantPermission.FOLLOWUP_EVALUATE);
        verifyNoInteractions(recommendations);
    }

    @Test
    void requestRecommendationRejectsNonpositiveTimeoutWithBadRequest() throws Exception {
        mvc.perform(post("/api/customers/{id}/recommendation", customerId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"timeoutMs\": 0}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/customers/{id}/recommendation", customerId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"timeoutMs\": -100}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(recommendations);
    }

    @Test
    void generateDraftReturnsAvailableMessageDraftWithETag() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        var evaluation = testEvaluation(FollowUpStatus.DUE);
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Ana, te saludamos de Café.",
                DraftVariables.empty(),
                "es-419",
                List.of("Compra reciente: Café"),
                List.of(),
                "Follow-up draft",
                RecommendationConfidence.of(0.9));

        when(drafts.draftSafe(eq(cId), any(), any(), any(), eq(null)))
                .thenReturn(FollowUpDraftResult.available(evaluation, draft, 1L));

        mvc.perform(post("/api/customers/{id}/draft", customerId))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.draft.body").value("Hola Ana, te saludamos de Café."))
                .andExpect(jsonPath("$.draft.locale").value("es-419"))
                .andExpect(jsonPath("$.draft.action").value("REPEAT_PURCHASE_FOLLOW_UP"))
                .andExpect(jsonPath("$.draft.templateIntent").value("REPEAT_PURCHASE"));
    }

    @Test
    void generateDraftAliasEndpointReturnsDraft() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        var evaluation = testEvaluation(FollowUpStatus.DUE);
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Ana, te saludamos de Café.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up draft",
                RecommendationConfidence.of(0.9));

        when(drafts.draftSafe(eq(cId), any(), any(), any(), eq(null)))
                .thenReturn(FollowUpDraftResult.available(evaluation, draft, 2L));

        mvc.perform(post("/api/customers/{id}/follow-up-draft", customerId))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void generateDraftWithIfMatchHeaderPassesVersion() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        var evaluation = testEvaluation(FollowUpStatus.DUE);
        MessageDraft draft = new MessageDraft(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE,
                "Hola Ana.",
                DraftVariables.empty(),
                "es-419",
                List.of(),
                List.of(),
                "Follow-up",
                RecommendationConfidence.of(0.8));

        when(drafts.draftSafe(eq(cId), any(), any(), any(), eq(5L)))
                .thenReturn(FollowUpDraftResult.available(evaluation, draft, 5L));

        mvc.perform(post("/api/customers/{id}/draft", customerId)
                .header("If-Match", "\"5\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"5\""));

        verify(drafts).draftSafe(eq(cId), any(), any(), any(), eq(5L));
    }

    @Test
    void generateDraftReturnsRefusalWhenModelRefuses() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        var evaluation = testEvaluation(FollowUpStatus.DUE);
        NoDraft refusal = new NoDraft(
                NoDraftReason.SAFETY_VIOLATION,
                "Prompt injection detected in notes",
                RecommendationConfidence.of(0.99));

        when(drafts.draftSafe(eq(cId), any(), any(), any(), eq(null)))
                .thenReturn(FollowUpDraftResult.refusal(evaluation, refusal, 1L));

        mvc.perform(post("/api/customers/{id}/draft", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_DRAFT"))
                .andExpect(jsonPath("$.refusalReason").value("SAFETY_VIOLATION"))
                .andExpect(jsonPath("$.refusal.reason").value("SAFETY_VIOLATION"))
                .andExpect(jsonPath("$.refusal.rationale").value("Prompt injection detected in notes"));
    }

    @Test
    void generateDraftReturnsConflictOnStaleVersion() throws Exception {
        CustomerId cId = new CustomerId(customerId);
        when(drafts.draftSafe(eq(cId), any(), any(), any(), eq(1L)))
                .thenThrow(new FollowUpConflictException());

        mvc.perform(post("/api/customers/{id}/draft", customerId)
                .header("If-Match", "\"1\""))
                .andExpect(status().isConflict());
    }

    @Test
    void generateDraftRejectsUnauthorizedCallerWithoutInvokingService() throws Exception {
        var authorization = mock(io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService.class);
        org.mockito.Mockito.doThrow(new io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException("Forbidden"))
                .when(authorization).requirePermission(io.github.stevdrey.dokene.tenant.domain.TenantPermission.MESSAGE_DRAFT);

        MockMvc customMvc = MockMvcBuilders.standaloneSetup(new FollowUpController(service, recommendations, drafts, null, null, authorization))
                .setControllerAdvice(new FollowUpExceptionHandler()).build();

        customMvc.perform(post("/api/customers/{id}/draft", customerId))
                .andExpect(status().isForbidden());

        verify(authorization).requirePermission(io.github.stevdrey.dokene.tenant.domain.TenantPermission.MESSAGE_DRAFT);
        verifyNoInteractions(drafts);
    }

    private FollowUpEvaluation testEvaluation(FollowUpStatus status) {
        boolean eligible = status == FollowUpStatus.DUE;
        LocalDate tenantDate = LocalDate.of(2026, 9, 25);
        Instant lastPurchase = Instant.parse("2026-08-01T12:00:00Z");
        return new FollowUpEvaluation(new CustomerId(customerId),
                status, List.of(eligible ? FollowUpReason.DUE_TODAY : FollowUpReason.DO_NOT_CONTACT),
                Instant.parse("2026-09-25T12:00:00Z"), tenantDate, ZoneId.of("America/Costa_Rica"),
                eligible ? tenantDate : null,
                eligible ? FollowUpTimingSource.LAST_PURCHASE : FollowUpTimingSource.NONE,
                eligible ? 30 : 0, eligible ? lastPurchase : null);
    }
}
