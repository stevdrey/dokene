// ABOUTME: Pins the follow-up work recovery contract (issue 461, ADR 0038): strict body and If-Match, permissions before
// ABOUTME: any write, 409 reasons for refused decisions, one audit row per applied decision and none for a replay.
package io.github.stevdrey.dokene.followup.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditTarget;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpRetryPolicy;
import io.github.stevdrey.dokene.followup.application.FollowUpRetryProperties;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkLedgerRepository;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkRecoveryService;
import io.github.stevdrey.dokene.followup.domain.FollowUpRecoverableWork;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkFailure;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkRecoveryAction;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkState;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FollowUpWorkRecoveryControllerTest {
    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");
    private static final TenantId TENANT_ID = TenantId.random();
    private static final UUID ITEM = UUID.randomUUID();
    private static final CustomerId CUSTOMER = new CustomerId(UUID.randomUUID());

    private final FollowUpWorkLedgerRepository ledger = mock();
    private final TenantAuthorizationService authorization = mock();
    private final TenantContextProvider contexts = mock();
    private final AuditRecorder audit = mock();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(contexts.requireCurrent()).thenReturn(new TenantContext(TENANT_ID, new IdentityId(UUID.randomUUID()),
                new TenantMembershipId(UUID.randomUUID()), TenantRole.OPERATOR));
        FollowUpRetryPolicy retries = new FollowUpRetryPolicy(new FollowUpRetryProperties(null, null, null, 2));
        mvc = MockMvcBuilders.standaloneSetup(new FollowUpWorkRecoveryController(new FollowUpWorkRecoveryService(
                ledger, authorization, contexts, audit, retries, Clock.fixed(NOW, ZoneOffset.UTC)))).build();
    }

    @Test
    void listsItemsAwaitingADecisionWithoutCustomerData() throws Exception {
        when(ledger.findRecoverable(TENANT_ID, 50)).thenReturn(List.of(
                item(FollowUpWorkState.EXHAUSTED, FollowUpWorkFailure.OUTCOME_UNKNOWN, 0, null)));

        mvc.perform(get("/api/follow-up-work/recovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].candidateId").value(ITEM.toString()))
                .andExpect(jsonPath("$[0].customerId").value(CUSTOMER.value().toString()))
                .andExpect(jsonPath("$[0].state").value("EXHAUSTED"))
                .andExpect(jsonPath("$[0].lastFailure").value("OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$[0].outcomeUnknown").value(true))
                .andExpect(jsonPath("$[0].recoveryCount").value(0))
                .andExpect(jsonPath("$[0].claimedBy").doesNotExist())
                .andExpect(jsonPath("$[0].claimToken").doesNotExist());
        verify(authorization).requirePermission(TenantPermission.FOLLOWUP_READ);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void rejectsAListLimitOutsideTheBound(int limit) throws Exception {
        mvc.perform(get("/api/follow-up-work/recovery").param("limit", String.valueOf(limit)))
                .andExpect(status().isBadRequest());
        verify(ledger, never()).findRecoverable(any(), anyInt());
    }

    @Test
    void requeuesASafeFailureAndAuditsIt() throws Exception {
        when(ledger.lockRecoverable(TENANT_ID, ITEM)).thenReturn(
                Optional.of(item(FollowUpWorkState.EXHAUSTED, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE, 0, null)),
                Optional.of(item(FollowUpWorkState.RETRY_DUE, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE, 1,
                        FollowUpWorkRecoveryAction.REQUEUE)));
        when(ledger.requeue(TENANT_ID, ITEM, 0, NOW)).thenReturn(true);

        recover("\"0\"", "{\"action\":\"REQUEUE\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.state").value("RETRY_DUE"))
                .andExpect(jsonPath("$.lastRecoveryAction").value("REQUEUE"));
        verify(authorization).requirePermission(TenantPermission.FOLLOWUP_WRITE);
        verify(audit).followUpMutated(AuditTarget.Type.CUSTOMER, CUSTOMER.value(),
                AuditEventType.FOLLOW_UP_WORK_REQUEUED);
    }

    @Test
    void resolvesAnUnknownOutcomeAndAuditsIt() throws Exception {
        when(ledger.lockRecoverable(TENANT_ID, ITEM)).thenReturn(
                Optional.of(item(FollowUpWorkState.EXHAUSTED, FollowUpWorkFailure.OUTCOME_UNKNOWN, 0, null)),
                Optional.of(item(FollowUpWorkState.COMPLETED, FollowUpWorkFailure.OUTCOME_UNKNOWN, 1,
                        FollowUpWorkRecoveryAction.RESOLVE)));
        when(ledger.resolve(TENANT_ID, ITEM, 0, NOW)).thenReturn(true);

        recover("\"0\"", "{\"action\":\"RESOLVE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));
        verify(audit).followUpMutated(AuditTarget.Type.CUSTOMER, CUSTOMER.value(),
                AuditEventType.FOLLOW_UP_WORK_RESOLVED);
    }

    @Test
    void replayingAnAppliedDecisionChangesAndAuditsNothing() throws Exception {
        when(ledger.lockRecoverable(TENANT_ID, ITEM)).thenReturn(Optional.of(item(FollowUpWorkState.COMPLETED,
                FollowUpWorkFailure.OUTCOME_UNKNOWN, 1, FollowUpWorkRecoveryAction.RESOLVE)));

        recover("\"0\"", "{\"action\":\"RESOLVE\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));
        verify(ledger, never()).resolve(any(), any(), anyInt(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void refusesToRequeueAnUnknownOutcome() throws Exception {
        when(ledger.lockRecoverable(TENANT_ID, ITEM)).thenReturn(
                Optional.of(item(FollowUpWorkState.EXHAUSTED, FollowUpWorkFailure.LEASE_EXPIRED, 0, null)));

        refused("\"0\"", "REQUEUE", "OUTCOME_UNKNOWN");
    }

    @Test
    void refusesAStaleVersionAnotherActionAtTheOldVersionAndAnItemNotAwaitingRecovery() throws Exception {
        when(ledger.lockRecoverable(TENANT_ID, ITEM)).thenReturn(Optional.of(item(FollowUpWorkState.COMPLETED,
                FollowUpWorkFailure.OUTCOME_UNKNOWN, 1, FollowUpWorkRecoveryAction.RESOLVE)));
        refused("\"0\"", "REQUEUE", "STALE_VERSION");
        refused("\"3\"", "RESOLVE", "STALE_VERSION");
        refused("\"1\"", "RESOLVE", "NOT_EXHAUSTED");
    }

    @Test
    void refusesARequeuePastTheLimitOrWhileTheCustomerHasOtherOpenWork() throws Exception {
        when(ledger.lockRecoverable(TENANT_ID, ITEM)).thenReturn(
                Optional.of(item(FollowUpWorkState.EXHAUSTED, FollowUpWorkFailure.REJECTED, 2,
                        FollowUpWorkRecoveryAction.REQUEUE)));
        refused("\"2\"", "REQUEUE", "REQUEUE_LIMIT_REACHED");

        when(ledger.lockRecoverable(TENANT_ID, ITEM)).thenReturn(
                Optional.of(item(FollowUpWorkState.EXHAUSTED, FollowUpWorkFailure.REJECTED, 0, null)));
        when(ledger.requeue(TENANT_ID, ITEM, 0, NOW)).thenReturn(false);
        refused("\"0\"", "REQUEUE", "CUSTOMER_HAS_NEWER_WORK");

        when(ledger.requeue(TENANT_ID, ITEM, 0, NOW)).thenThrow(new DuplicateKeyException("open item"));
        refused("\"0\"", "REQUEUE", "CUSTOMER_HAS_NEWER_WORK");
    }

    @Test
    void anUnknownItemIsNotFound() throws Exception {
        when(ledger.lockRecoverable(TENANT_ID, ITEM)).thenReturn(Optional.empty());
        recover("\"0\"", "{\"action\":\"RESOLVE\"}").andExpect(status().isNotFound());
        verifyNoInteractions(audit);
    }

    @Test
    void aMemberWithoutTheWritePermissionChangesNothing() throws Exception {
        doThrow(new TenantAccessDeniedException("denied")).when(authorization)
                .requirePermission(TenantPermission.FOLLOWUP_WRITE);
        recover("\"0\"", "{\"action\":\"RESOLVE\"}").andExpect(status().isForbidden());
        verifyNoInteractions(ledger, audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"action\":null}", "{\"action\":\"REPLAY\"}", "{\"action\":\"resolve\"}",
            "{\"action\":\" RESOLVE\"}", "{\"action\":\"RESOLVE\",\"x\":1}",
            "[]", "\"RESOLVE\""})
    void rejectsAMalformedBody(String body) throws Exception {
        recover("\"0\"", body).andExpect(status().isBadRequest());
        verifyNoInteractions(ledger, audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"W/\"0\"", "0", "\"-1\"", "\"x\"", "\"0\", \"1\"", "\"9999999999\""})
    void rejectsAnInvalidIfMatch(String ifMatch) throws Exception {
        recover(ifMatch, "{\"action\":\"RESOLVE\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(ledger, audit);
    }

    @Test
    void requiresIfMatch() throws Exception {
        mvc.perform(post("/api/follow-up-work/recovery/" + ITEM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"RESOLVE\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(ledger, audit);
    }

    private void refused(String ifMatch, String action, String reason) throws Exception {
        recover(ifMatch, "{\"action\":\"" + action + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value(reason));
        verify(audit, never()).followUpMutated(any(), any(), any());
    }

    private ResultActions recover(String ifMatch, String body) throws Exception {
        return mvc.perform(post("/api/follow-up-work/recovery/" + ITEM).header("If-Match", ifMatch)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static FollowUpRecoverableWork item(FollowUpWorkState state, FollowUpWorkFailure failure,
            int recoveryCount, FollowUpWorkRecoveryAction lastAction) {
        return new FollowUpRecoverableWork(ITEM, CUSTOMER, LocalDate.of(2026, 11, 20), state, failure, 3,
                Optional.of(NOW.minusSeconds(60)), recoveryCount, Optional.ofNullable(lastAction));
    }
}
