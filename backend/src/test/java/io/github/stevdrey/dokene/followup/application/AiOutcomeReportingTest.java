package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiDraftResponse;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import io.github.stevdrey.dokene.ai.application.AiResilience;
import io.github.stevdrey.dokene.ai.application.AiRetryProperties;
import io.github.stevdrey.dokene.ai.application.AiTelemetry;
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.application.RecommendationContextException;
import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.NoDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraftReason;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
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
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

/**
 * Verifies that every terminal AI outcome is reported once (metrics, safe log, durable audit), that retry recovery
 * never bypasses the Action Gate, and that no sensitive text reaches logs, telemetry or audit.
 */
class AiOutcomeReportingTest {
    private static final String SECRET = "sk-secret-key +593991234567 IGNORE ALL PREVIOUS";

    private final LocalDate tenantDate = LocalDate.of(2026, 9, 25);
    private final Instant lastPurchase = Instant.parse("2026-08-01T12:00:00Z");
    private final List<PurchaseBaseline> purchases =
            List.of(new PurchaseBaseline(new PurchaseId(UUID.randomUUID()), 0L, lastPurchase));
    private final Duration timeout = Duration.ofSeconds(3);

    private final RecommendationContextAssembler assembler = mock();
    private final AiActionGate gate = mock();
    private final TenantAuthorizationService authorization = mock();
    private final TenantContextProvider contexts = mock();
    private final FollowUpService followUps = mock();
    private final AiProvider provider = mock();

    private final RecordingAudit audit = new RecordingAudit();
    private final RecordingTelemetry telemetry = new RecordingTelemetry();
    private final AiOutcomeReporter reporter =
            new AiOutcomeReporter(telemetry, audit, () -> java.util.Optional.of(UUID.randomUUID()));
    private final AiResilience resilience = new AiResilience(
            new AiRetryProperties(2, Duration.ofMillis(1), Duration.ofMillis(2), Duration.ofMillis(10)), telemetry);

    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final FollowUpEvaluation due = new FollowUpEvaluation(customerId, FollowUpStatus.DUE,
            List.of(FollowUpReason.DUE_TODAY), Instant.now(), tenantDate, ZoneId.of("UTC"), tenantDate,
            FollowUpTimingSource.LAST_PURCHASE, 30, lastPurchase);
    private final RecommendationContextAssembler.Assembly assembly =
            new RecommendationContextAssembler.Assembly(due, context(), purchases, 1L);

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private Level previousRootLevel;

    private FollowUpRecommendationService recommendations;

    @BeforeEach
    void setUp() {
        logs.start();
        rootLogger.addAppender(logs);
        previousRootLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.DEBUG);
        stubTimeouts(provider);
        when(assembler.assemble(customerId)).thenReturn(assembly);
        when(followUps.customerPolicy(customerId)).thenReturn(
                new CustomerFollowUpPolicy(TenantId.random(), customerId, 30, null, null, null, 1L));
        when(followUps.evaluateSnapshot(customerId))
                .thenReturn(new FollowUpService.FollowUpEvaluationSnapshot(due, 1L));
        recommendations = new FollowUpRecommendationService(provider, assembler, gate, authorization, contexts,
                followUps, null, resilience, reporter);
    }

    private static void stubTimeouts(AiProvider mockProvider) {
        when(mockProvider.defaultTimeout()).thenReturn(Duration.ofSeconds(15));
        when(mockProvider.maxTimeout()).thenReturn(Duration.ofSeconds(30));
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        // The root logger is JVM-global: leaving DEBUG on would change every later test in this worker.
        rootLogger.setLevel(previousRootLevel);
    }

    @Test
    void generatedRecommendationIsAuditedOnceWithoutDetail() {
        ActionRecommendation action = action();
        when(provider.recommend(any())).thenReturn(new AiRecommendationResponse(action, success()));
        when(gate.evaluate(eq(customerId), eq(assembly), eq(action)))
                .thenReturn(ActionGateDecision.accepted(action, due, 1L));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        assertThat(result.status()).isEqualTo(RecommendationStatus.AVAILABLE);
        assertThat(audit.events).containsExactly("generated:NEXT_BEST_ACTION");
        assertThat(telemetry.refusals).isEmpty();
        assertThat(telemetry.gateRejections).isEmpty();
    }

    @Test
    void explicitModelRefusalIsDistinguishedFromGeneration() {
        NoRecommendation refusal = new NoRecommendation(NoRecommendationReason.UNCERTAIN_INTENT,
                "Insufficient signal", RecommendationConfidence.of(0.4));
        when(provider.recommend(any())).thenReturn(new AiRecommendationResponse(refusal, success()));
        when(gate.evaluate(eq(customerId), eq(assembly), eq(refusal)))
                .thenReturn(ActionGateDecision.accepted(refusal, due, 1L));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        assertThat(result.status()).isEqualTo(RecommendationStatus.NO_RECOMMENDATION);
        assertThat(audit.events).containsExactly("modelRefused:NEXT_BEST_ACTION");
        assertThat(telemetry.refusals).containsExactly(AiOperation.NEXT_BEST_ACTION);
    }

    @Test
    void gateRejectionIsReportedWithTheUnlossyReasonAndNotAsAFailure() {
        ActionRecommendation action = action();
        when(provider.recommend(any())).thenReturn(new AiRecommendationResponse(action, success()));
        when(gate.evaluate(eq(customerId), eq(assembly), eq(action))).thenReturn(ActionGateDecision.rejected(
                ActionGateRejectionReason.DISALLOWED_ACTION, "diagnostic", due, action, 1L));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        assertThat(result.status()).isEqualTo(RecommendationStatus.AI_UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.DISALLOWED_ACTION);
        assertThat(result.unavailableReason().retryable()).isFalse();
        assertThat(audit.events).containsExactly("gateRejected:NEXT_BEST_ACTION:DISALLOWED_ACTION");
        assertThat(telemetry.gateRejections).containsExactly("NEXT_BEST_ACTION:DISALLOWED_ACTION");
    }

    @Test
    void providerFailureIsAuditedAsFailedAndLeavesTheEvaluationUsable() {
        when(provider.recommend(any())).thenThrow(failure(AiFailureCategory.REJECTED_REQUEST));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        assertThat(result.status()).isEqualTo(RecommendationStatus.AI_UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.REJECTED_REQUEST);
        assertThat(result.evaluation()).isSameAs(due);
        assertThat(audit.events).containsExactly("failed:NEXT_BEST_ACTION:REJECTED_REQUEST");
        verify(provider, times(1)).recommend(any());
    }

    @Test
    void notAvailableProviderIsNotRetriedAndIsNotRetryableForClients() {
        when(provider.recommend(any())).thenThrow(failure(AiFailureCategory.NOT_AVAILABLE));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.NOT_AVAILABLE);
        assertThat(result.unavailableReason().retryable()).isFalse();
        verify(provider, times(1)).recommend(any());
    }

    @Test
    void contextFailureIsAuditedAsFailedWithContextReason() {
        when(assembler.assemble(customerId))
                .thenThrow(new RecommendationContextException(RecommendationContextException.Reason.TOO_LARGE));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.CONTEXT_TOO_LARGE);
        assertThat(audit.events).containsExactly("failed:NEXT_BEST_ACTION:CONTEXT_TOO_LARGE");
    }

    @Test
    void temporaryProviderRecoveryStillGoesThroughTheActionGate() {
        ActionRecommendation action = action();
        when(provider.recommend(any()))
                .thenThrow(failure(AiFailureCategory.THROTTLED))
                .thenReturn(new AiRecommendationResponse(action, success()));
        when(gate.evaluate(eq(customerId), eq(assembly), eq(action))).thenReturn(ActionGateDecision.rejected(
                ActionGateRejectionReason.INVALID_RECOMMENDATION, "diagnostic", due, action, 1L));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        verify(provider, times(2)).recommend(any());
        verify(gate, times(1)).evaluate(eq(customerId), eq(assembly), eq(action));
        assertThat(result.status()).isEqualTo(RecommendationStatus.AI_UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.INVALID_RECOMMENDATION);
        assertThat(telemetry.retries).containsExactly("NEXT_BEST_ACTION:THROTTLED");
        assertThat(audit.events).containsExactly("gateRejected:NEXT_BEST_ACTION:INVALID_RECOMMENDATION");
    }

    @Test
    void temporaryProviderRecoveryYieldsGeneratedWhenGateAccepts() {
        ActionRecommendation action = action();
        when(provider.recommend(any()))
                .thenThrow(failure(AiFailureCategory.TIMEOUT))
                .thenReturn(new AiRecommendationResponse(action, success()));
        when(gate.evaluate(eq(customerId), eq(assembly), eq(action)))
                .thenReturn(ActionGateDecision.accepted(action, due, 1L));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        assertThat(result.status()).isEqualTo(RecommendationStatus.AVAILABLE);
        assertThat(audit.events).containsExactly("generated:NEXT_BEST_ACTION");
        // two provider attempts, exactly one logical outcome for the request
        assertThat(telemetry.invocations).hasSize(2);
        assertThat(telemetry.outcomes).containsExactly("NEXT_BEST_ACTION:GENERATED");
    }

    @Test
    void exhaustedTransientFailureIsRetryableForClients() {
        when(provider.recommend(any())).thenThrow(failure(AiFailureCategory.UNAVAILABLE));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        verify(provider, times(2)).recommend(any());
        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.UNAVAILABLE);
        assertThat(result.unavailableReason().retryable()).isTrue();
        assertThat(audit.events).containsExactly("failed:NEXT_BEST_ACTION:UNAVAILABLE");
    }

    @Test
    void acceptedOutputDiscardedByAVersionConflictIsAuditedAsStaleNotGenerated() {
        ActionRecommendation action = action();
        when(provider.recommend(any())).thenReturn(new AiRecommendationResponse(action, success()));
        // the client's If-Match (1) matches the policy read before the call, but the gate saw a newer version (2)
        when(gate.evaluate(eq(customerId), eq(assembly), eq(action)))
                .thenReturn(ActionGateDecision.accepted(action, due, 2L));

        assertThatThrownBy(() -> recommendations.recommendSafe(customerId, timeout, 1L))
                .isInstanceOf(FollowUpConflictException.class);

        assertThat(audit.events).containsExactly("gateRejected:NEXT_BEST_ACTION:STALE_STATE");
        assertThat(telemetry.outcomes).containsExactly("NEXT_BEST_ACTION:GATE_REJECTED");
    }

    @Test
    void acceptedOutputDiscardedByPolicyDriftIsAuditedAsStaleNotGenerated() {
        ActionRecommendation action = action();
        when(provider.recommend(any())).thenReturn(new AiRecommendationResponse(action, success()));
        when(gate.evaluate(eq(customerId), eq(assembly), eq(action)))
                .thenReturn(ActionGateDecision.accepted(action, due, 1L));
        // policy read at the start is version 1; by the time the gate result is checked it is version 2
        when(followUps.customerPolicy(customerId))
                .thenReturn(new CustomerFollowUpPolicy(TenantId.random(), customerId, 30, null, null, null, 1L))
                .thenReturn(new CustomerFollowUpPolicy(TenantId.random(), customerId, 30, null, null, null, 2L));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        assertThat(result.status()).isEqualTo(RecommendationStatus.STALE_STATE);
        assertThat(audit.events).containsExactly("gateRejected:NEXT_BEST_ACTION:STALE_STATE");
    }

    @Test
    void deterministicallyIneligibleCustomerNeverReachesTheProviderAndIsNotReported() {
        FollowUpEvaluation ineligible = new FollowUpEvaluation(customerId, FollowUpStatus.INELIGIBLE,
                List.of(FollowUpReason.DO_NOT_CONTACT), Instant.now(), tenantDate, ZoneId.of("UTC"), tenantDate,
                FollowUpTimingSource.LAST_PURCHASE, 30, lastPurchase);
        when(assembler.assemble(customerId))
                .thenReturn(new RecommendationContextAssembler.Assembly(ineligible, context(), purchases, 1L));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        assertThat(result.status()).isEqualTo(RecommendationStatus.INELIGIBLE);
        verify(provider, times(0)).recommend(any());
        assertThat(audit.events).isEmpty();
        assertThat(telemetry.outcomes).isEmpty();
    }

    @Test
    void draftDiscardedByAVersionConflictIsAuditedAsStaleNotGenerated() {
        AiProvider draftProvider = mock();
        stubTimeouts(draftProvider);
        FollowUpDraftService drafts = new FollowUpDraftService(draftProvider, assembler, gate, authorization,
                mock(TenantContextProvider.class), followUps, null, null, resilience, reporter);
        NoDraft noDraft = new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY, "No draft", RecommendationConfidence.of(0.5));
        when(draftProvider.draft(any())).thenReturn(new AiDraftResponse(noDraft, success()));
        when(gate.evaluateDraft(eq(customerId), eq(assembly), eq(noDraft), any(), any(), any()))
                .thenReturn(DraftGateDecision.accepted(noDraft, due, 2L));

        assertThatThrownBy(() -> drafts.draftSafe(customerId, null, null, timeout, 1L))
                .isInstanceOf(FollowUpConflictException.class);

        assertThat(audit.events).containsExactly("gateRejected:MESSAGE_DRAFT:STALE_STATE");
    }

    @Test
    void noSensitiveTextReachesLogsTelemetryOrAudit() {
        when(provider.recommend(any())).thenThrow(
                new IllegalStateException(SECRET, new RuntimeException(SECRET)));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.UNAVAILABLE);
        String observed = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                + audit.events + telemetry.all();
        assertThat(observed).doesNotContain("sk-secret").doesNotContain("991234567").doesNotContain("IGNORE");
        assertThat(logs.list).noneMatch(event -> event.getThrowableProxy() != null);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("operation=NEXT_BEST_ACTION").contains("outcome=FAILED").contains("detail=UNAVAILABLE"));
    }

    @ParameterizedTest
    @EnumSource(value = ActionGateRejectionReason.class,
            names = {"NO_TENANT_CONTEXT", "UNAUTHORIZED", "CUSTOMER_NOT_FOUND"})
    void tenantBoundaryRejectionsAreCountedAndLoggedButNeverAuditedWithTheUnverifiedCustomerId(
            ActionGateRejectionReason reason) {
        ActionRecommendation action = action();
        when(provider.recommend(any())).thenReturn(new AiRecommendationResponse(action, success()));
        when(gate.evaluate(eq(customerId), eq(assembly), eq(action)))
                .thenReturn(ActionGateDecision.rejected(reason, "boundary"));

        assertThatThrownBy(() -> recommendations.recommendSafe(customerId, timeout, null))
                .isInstanceOf(reason == ActionGateRejectionReason.CUSTOMER_NOT_FOUND
                        ? io.github.stevdrey.dokene.customer.application.CustomerNotFoundException.class
                        : io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException.class);

        assertThat(telemetry.gateRejections).containsExactly("NEXT_BEST_ACTION:" + reason.name());
        assertThat(telemetry.outcomes).containsExactly("NEXT_BEST_ACTION:GATE_REJECTED");
        assertThat(audit.events).isEmpty();
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("outcome=GATE_REJECTED").contains("detail=" + reason.name()));
    }

    @ParameterizedTest
    @EnumSource(value = ActionGateRejectionReason.class,
            names = {"NO_TENANT_CONTEXT", "UNAUTHORIZED", "CUSTOMER_NOT_FOUND"})
    void draftTenantBoundaryRejectionsAreNeverAudited(ActionGateRejectionReason reason) {
        AiProvider draftProvider = mock();
        stubTimeouts(draftProvider);
        FollowUpDraftService drafts = new FollowUpDraftService(draftProvider, assembler, gate, authorization,
                mock(TenantContextProvider.class), followUps, null, null, resilience, reporter);
        NoDraft noDraft = new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY, "No draft", RecommendationConfidence.of(0.5));
        when(draftProvider.draft(any())).thenReturn(new AiDraftResponse(noDraft, success()));
        when(gate.evaluateDraft(eq(customerId), eq(assembly), eq(noDraft), any(), any(), any()))
                .thenReturn(DraftGateDecision.rejected(reason, "boundary"));

        assertThatThrownBy(() -> drafts.draftSafe(customerId, null, null, timeout, null))
                .isInstanceOfAny(io.github.stevdrey.dokene.customer.application.CustomerNotFoundException.class,
                        io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException.class);

        assertThat(telemetry.gateRejections).containsExactly("MESSAGE_DRAFT:" + reason.name());
        assertThat(audit.events).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ActionGateRejectionReason.class, names = {"UNAUTHORIZED", "CUSTOMER_NOT_FOUND"})
    void authorizationLostWhileAProviderFailureIsInFlightStillCountsAndLogsTheOutcome(
            ActionGateRejectionReason reason) {
        when(provider.recommend(any())).thenThrow(failure(AiFailureCategory.REJECTED_REQUEST));
        RuntimeException lost = boundaryException(reason);
        org.mockito.Mockito.doThrow(lost).when(gate).revalidateAuthorization(customerId);

        assertThatThrownBy(() -> recommendations.recommendSafe(customerId, timeout, null)).isSameAs(lost);

        assertThat(telemetry.outcomes).containsExactly("NEXT_BEST_ACTION:GATE_REJECTED");
        assertThat(telemetry.gateRejections).containsExactly("NEXT_BEST_ACTION:" + reason.name());
        assertThat(audit.events).isEmpty();
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("outcome=GATE_REJECTED").contains("detail=" + reason.name()));
    }

    @ParameterizedTest
    @EnumSource(value = ActionGateRejectionReason.class, names = {"UNAUTHORIZED", "CUSTOMER_NOT_FOUND"})
    void draftAuthorizationLostWhileAProviderFailureIsInFlightStillCountsAndLogsTheOutcome(
            ActionGateRejectionReason reason) {
        AiProvider draftProvider = mock();
        stubTimeouts(draftProvider);
        FollowUpDraftService drafts = new FollowUpDraftService(draftProvider, assembler, gate, authorization,
                mock(TenantContextProvider.class), followUps, null, null, resilience, reporter);
        when(draftProvider.draft(any())).thenThrow(failure(AiFailureCategory.REJECTED_REQUEST));
        RuntimeException lost = boundaryException(reason);
        // the first revalidation is the fail-closed check before data reaches the provider; the second is the failure path
        org.mockito.Mockito.doNothing().doThrow(lost).when(gate).revalidateDraftAuthorization(customerId);

        assertThatThrownBy(() -> drafts.draftSafe(customerId, null, null, timeout, null)).isSameAs(lost);

        assertThat(telemetry.outcomes).containsExactly("MESSAGE_DRAFT:GATE_REJECTED");
        assertThat(telemetry.gateRejections).containsExactly("MESSAGE_DRAFT:" + reason.name());
        assertThat(audit.events).isEmpty();
    }

    private static RuntimeException boundaryException(ActionGateRejectionReason reason) {
        return reason == ActionGateRejectionReason.CUSTOMER_NOT_FOUND
                ? new io.github.stevdrey.dokene.customer.application.CustomerNotFoundException()
                : new io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException("revoked");
    }

    @Test
    void retryRevalidatesAuthorizationBeforeTheSecondProviderCall() {
        ActionRecommendation action = action();
        when(provider.recommend(any()))
                .thenThrow(failure(AiFailureCategory.THROTTLED))
                .thenReturn(new AiRecommendationResponse(action, success()));
        when(gate.evaluate(eq(customerId), eq(assembly), eq(action)))
                .thenReturn(ActionGateDecision.accepted(action, due, 1L));

        FollowUpRecommendationResult result = recommendations.recommendSafe(customerId, timeout, null);

        assertThat(result.status()).isEqualTo(RecommendationStatus.AVAILABLE);
        verify(provider, times(2)).recommend(any());
        // the only revalidation on this path is the pre-retry guard
        verify(gate, times(1)).revalidateAuthorization(customerId);
    }

    @ParameterizedTest
    @EnumSource(value = ActionGateRejectionReason.class, names = {"UNAUTHORIZED", "CUSTOMER_NOT_FOUND"})
    void authorizationRevokedBetweenAttemptsAbortsTheRetryBeforeContextIsSentAgain(
            ActionGateRejectionReason reason) {
        when(provider.recommend(any())).thenThrow(failure(AiFailureCategory.THROTTLED));
        RuntimeException lost = boundaryException(reason);
        org.mockito.Mockito.doThrow(lost).when(gate).revalidateAuthorization(customerId);

        assertThatThrownBy(() -> recommendations.recommendSafe(customerId, timeout, null)).isSameAs(lost);

        verify(provider, times(1)).recommend(any());
        assertThat(telemetry.retries).isEmpty();
        assertThat(telemetry.outcomes).containsExactly("NEXT_BEST_ACTION:GATE_REJECTED");
        assertThat(telemetry.gateRejections).containsExactly("NEXT_BEST_ACTION:" + reason.name());
        assertThat(audit.events).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ActionGateRejectionReason.class, names = {"UNAUTHORIZED", "CUSTOMER_NOT_FOUND"})
    void draftAuthorizationRevokedBetweenAttemptsAbortsTheRetryBeforeContextIsSentAgain(
            ActionGateRejectionReason reason) {
        AiProvider draftProvider = mock();
        stubTimeouts(draftProvider);
        FollowUpDraftService drafts = new FollowUpDraftService(draftProvider, assembler, gate, authorization,
                mock(TenantContextProvider.class), followUps, null, null, resilience, reporter);
        when(draftProvider.draft(any())).thenThrow(failure(AiFailureCategory.THROTTLED));
        RuntimeException lost = boundaryException(reason);
        // 1st: the fail-closed check before data reaches the provider; 2nd: the pre-retry guard
        org.mockito.Mockito.doNothing().doThrow(lost).when(gate).revalidateDraftAuthorization(customerId);

        assertThatThrownBy(() -> drafts.draftSafe(customerId, null, null, timeout, null)).isSameAs(lost);

        verify(draftProvider, times(1)).draft(any());
        assertThat(telemetry.retries).isEmpty();
        assertThat(telemetry.outcomes).containsExactly("MESSAGE_DRAFT:GATE_REJECTED");
        assertThat(telemetry.gateRejections).containsExactly("MESSAGE_DRAFT:" + reason.name());
        assertThat(audit.events).isEmpty();
    }

    @Test
    void draftOutcomesAreReportedWithTheDraftOperation() {
        TenantContextProvider tenantContexts = mock();
        AiProvider draftProvider = mock();
        stubTimeouts(draftProvider);
        FollowUpDraftService drafts = new FollowUpDraftService(draftProvider, assembler, gate, authorization,
                tenantContexts, followUps, null, null, resilience, reporter);
        NoDraft noDraft = new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY, "No draft", RecommendationConfidence.of(0.5));
        when(draftProvider.draft(any()))
                .thenThrow(failure(AiFailureCategory.THROTTLED))
                .thenReturn(new AiDraftResponse(noDraft, success()));
        when(gate.evaluateDraft(eq(customerId), eq(assembly), eq(noDraft), any(), any(), any()))
                .thenReturn(DraftGateDecision.accepted(noDraft, due, 1L));

        FollowUpDraftResult result = drafts.draftSafe(customerId, null, null, timeout, null);

        assertThat(result.status()).isEqualTo(DraftStatus.NO_DRAFT);
        verify(draftProvider, times(2)).draft(any());
        assertThat(audit.events).containsExactly("modelRefused:MESSAGE_DRAFT");
        assertThat(telemetry.retries).containsExactly("MESSAGE_DRAFT:THROTTLED");
    }

    @Test
    void draftGateRejectionAndFailureAreDistinct() {
        AiProvider draftProvider = mock();
        stubTimeouts(draftProvider);
        FollowUpDraftService drafts = new FollowUpDraftService(draftProvider, assembler, gate, authorization,
                mock(TenantContextProvider.class), followUps, null, null, resilience, reporter);
        NoDraft noDraft = new NoDraft(NoDraftReason.SAFETY_VIOLATION, "Unsafe", RecommendationConfidence.of(0.5));
        when(draftProvider.draft(any())).thenReturn(new AiDraftResponse(noDraft, success()));
        when(gate.evaluateDraft(eq(customerId), eq(assembly), eq(noDraft), any(), any(), any()))
                .thenReturn(DraftGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION, "x", due,
                        noDraft, 1L));

        FollowUpDraftResult rejected = drafts.draftSafe(customerId, null, null, timeout, null);

        assertThat(rejected.status()).isEqualTo(DraftStatus.AI_UNAVAILABLE);
        assertThat(audit.events).containsExactly("gateRejected:MESSAGE_DRAFT:INVALID_RECOMMENDATION");

        audit.events.clear();
        when(draftProvider.draft(any())).thenThrow(failure(AiFailureCategory.INVALID_STRUCTURED_RESPONSE));

        FollowUpDraftResult failed = drafts.draftSafe(customerId, null, null, timeout, null);

        assertThat(failed.unavailableReason()).isEqualTo(AiUnavailableReason.INVALID_STRUCTURED_RESPONSE);
        assertThat(audit.events).containsExactly("failed:MESSAGE_DRAFT:INVALID_STRUCTURED_RESPONSE");
    }

    private ActionRecommendation action() {
        return new ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Purchase cadence reached",
                RecommendationConfidence.of(0.8), DraftVariables.empty());
    }

    private RecommendationContext context() {
        return new RecommendationContext(
                new RecommendationContext.TrustedFacts(tenantDate, "DUE", List.of(TrustedFollowUpReason.DUE_TODAY),
                        30, tenantDate, true, List.of(lastPurchase), Arrays.asList(SemanticAction.values())),
                new RecommendationContext.UntrustedText("Ana", "Cliente habitual", List.of("Cafe")));
    }

    private AiInvocationMetadata success() {
        return new AiInvocationMetadata("test", "test-model", "req-1", Duration.ofMillis(10), null,
                AiCompletionStatus.SUCCEEDED);
    }

    private AiProviderException failure(AiFailureCategory category) {
        return new AiProviderException(category, new AiInvocationMetadata("test", "test-model", null,
                Duration.ofMillis(5), null, AiCompletionStatus.FAILED));
    }

    private static final class RecordingAudit implements AiOutcomeAuditPort {
        final List<String> events = new ArrayList<>();

        @Override
        public void generated(CustomerId customerId, AiOperation operation) {
            events.add("generated:" + operation);
        }

        @Override
        public void modelRefused(CustomerId customerId, AiOperation operation) {
            events.add("modelRefused:" + operation);
        }

        @Override
        public void gateRejected(CustomerId customerId, AiOperation operation, ActionGateRejectionReason reason) {
            events.add("gateRejected:" + operation + ":" + reason);
        }

        @Override
        public void failed(CustomerId customerId, AiOperation operation, AiUnavailableReason reason) {
            events.add("failed:" + operation + ":" + reason);
        }
    }

    private static final class RecordingTelemetry implements AiTelemetry {
        final List<String> retries = new ArrayList<>();
        final List<AiOperation> refusals = new ArrayList<>();
        final List<String> gateRejections = new ArrayList<>();
        final List<String> invocations = new ArrayList<>();
        final List<String> outcomes = new ArrayList<>();

        @Override
        public void attemptCompleted(AiOperation operation, AiInvocationMetadata metadata,
                AiFailureCategory category) {
            invocations.add(operation + ":" + metadata.providerId() + ":" + category);
        }

        @Override
        public void retryScheduled(AiOperation operation, String providerId, AiFailureCategory category) {
            retries.add(operation + ":" + category);
        }

        @Override
        public void outcome(AiOperation operation, Outcome outcome) {
            outcomes.add(operation + ":" + outcome);
        }

        @Override
        public void modelRefusal(AiOperation operation) {
            refusals.add(operation);
        }

        @Override
        public void gateRejected(AiOperation operation, String reason) {
            gateRejections.add(operation + ":" + reason);
        }

        String all() {
            return retries + "" + refusals + gateRejections + invocations;
        }
    }
}
