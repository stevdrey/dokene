// ABOUTME: Unit tests of the recorder's denial budget hook and summary write (spec 0020): rows inside budget, silent
// ABOUTME: counting past it, the signals, the fail closed path when the budget breaks, and the four write results.
package io.github.stevdrey.dokene.audit.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.application.DenialAuditBudget;
import io.github.stevdrey.dokene.audit.application.DenialAuditBudget.Summary;
import io.github.stevdrey.dokene.audit.application.DenialAuditLimitProperties;
import io.github.stevdrey.dokene.audit.application.MicrometerDenialAuditTelemetry;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.audit.domain.AuditOutcome;
import io.github.stevdrey.dokene.audit.persistence.jdbc.TransactionalAuditRecorder.SummaryWrite;
import io.github.stevdrey.dokene.audit.testsupport.MutableClock;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(OutputCaptureExtension.class)
class TransactionalAuditRecorderDenialBudgetTest {
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String DRIVER_TEXT = "driver-text-with-host-and-statement";

    private final JdbcAuditStore store = mock(JdbcAuditStore.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final DatabaseContextSigner signer = mock(DatabaseContextSigner.class);
    private final AuditExecutionContext execution = new AuditExecutionContext();
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DenialAuditBudget budget = new DenialAuditBudget(
            new DenialAuditLimitProperties(10, 60, WINDOW, 1_000, Duration.ofMinutes(1)), clock);
    private final IdentityId identity = new IdentityId(UUID.randomUUID());
    private final TenantContext member = new TenantContext(TenantId.random(), new IdentityId(UUID.randomUUID()),
            TenantMembershipId.random(), TenantRole.VIEWER);
    private final SignedDatabaseContext capability =
            new SignedDatabaseContext("payload", "signature", Instant.now().plusSeconds(30));
    private TransactionalAuditRecorder recorder = recorder(budget);

    @BeforeEach
    void noTenantContext() {
        when(contexts.current()).thenReturn(Optional.empty());
        when(signer.issueAuditContext(any(), any(), any())).thenReturn(capability);
    }

    /** covers: AC-1, AC-13 */
    @Test
    void writesTenRowsThenCountsSignalsAndLogsOncePerKeyWindow(CapturedOutput output) {
        for (int denial = 0; denial < 13; denial++) {
            deny(identity);
        }

        ArgumentCaptor<AuditEvent> appended = ArgumentCaptor.forClass(AuditEvent.class);
        verify(store, times(10)).append(appended.capture(), isNull());
        assertThat(appended.getAllValues()).allSatisfy(event -> {
            assertThat(event.type()).isEqualTo(AuditEventType.AUTHORIZATION_DENIED);
            assertThat(event.metadata()).isEqualTo(new AuditMetadata.AuthorizationDenied(
                    TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT));
            assertThat(event.actorId()).isNull();
        });
        assertThat(registry.get("dokene.audit.denials.suppressed")
                .tags("reason", "NO_TENANT_CONTEXT", "cause", "KEY_BUDGET").counter().count()).isEqualTo(3);
        assertThat(output.getAll()).containsOnlyOnce("Authorization denial rows suppressed for this window")
                .contains("identity=" + identity.value(), "reason=NO_TENANT_CONTEXT", "permission=TENANT_READ",
                        "cause=KEY_BUDGET");
    }

    /** covers: AC-11, AC-13 */
    @Test
    void aDenialWithoutIdentityChargesTheSharedSlotAndLogsNoIdentity(CapturedOutput output) {
        for (int denial = 0; denial < 11; denial++) {
            execution.runWithCorrelation(UUID.randomUUID(), () -> recorder.authorizationDenied(
                    TenantPermission.AUDIT_READ, AuditDenialReason.UNSPECIFIED));
        }

        verify(store, times(10)).append(any(), isNull());
        assertThat(output.getAll()).contains("identity=unknown", "reason=NO_TENANT_CONTEXT");
    }

    /** covers: AC-3 */
    @Test
    void aTenantDenialIsChargedToTheContextIdentityAndKeepsItsReason(CapturedOutput output) {
        when(contexts.current()).thenReturn(Optional.of(member));

        for (int denial = 0; denial < 12; denial++) {
            execution.runWithCorrelation(UUID.randomUUID(), () -> recorder.authorizationDenied(
                    TenantPermission.CUSTOMER_WRITE, AuditDenialReason.INSUFFICIENT_PERMISSION, identity));
        }
        clock.advance(WINDOW);

        verify(store, times(10)).append(any(), eq(capability));
        Summary summary = budget.drainEnded().pending().getFirst();
        assertThat(summary.context()).isEqualTo(member);
        assertThat(summary.reason()).isEqualTo(AuditDenialReason.INSUFFICIENT_PERMISSION);
        assertThat(summary.suppressedCount()).isEqualTo(2);
        assertThat(output.getAll()).containsOnlyOnce("Authorization denial rows suppressed for this window")
                .contains("identity=" + member.identityId().value(), "reason=INSUFFICIENT_PERMISSION",
                        "permission=CUSTOMER_WRITE", "cause=KEY_BUDGET")
                .doesNotContain(identity.value().toString());
    }

    /** covers: AC-10, AC-13 */
    @Test
    void aFullTrackerIsLoggedOnceAsAnErrorAndCountedWithTheDenialsOwnReason(CapturedOutput output) {
        for (int key = 0; key < 1_000; key++) {
            deny(new IdentityId(UUID.randomUUID()));
        }

        deny(new IdentityId(UUID.randomUUID()));
        deny(new IdentityId(UUID.randomUUID()));

        verify(store, times(1_000)).append(any(), isNull());
        assertThat(output.getAll()).containsOnlyOnce("Denial audit tracker is full");
        assertThat(registry.get("dokene.audit.denials.suppressed")
                .tags("reason", "NO_TENANT_CONTEXT", "cause", "TRACKER_FULL").counter().count()).isEqualTo(2);
        assertThat(registry.get("dokene.audit.denials.tracked.keys").gauge().value()).isEqualTo(1_000);
    }

    /** covers: AC-14 */
    @Test
    void aBudgetThatThrowsStillGetsTheRowWritten(CapturedOutput output) {
        recorder = recorder(new DenialAuditBudget(
                new DenialAuditLimitProperties(10, 60, WINDOW, 1_000, Duration.ofMinutes(1)), clock) {
            @Override
            public synchronized Decision decide(TenantContext context, IdentityId callerIdentity,
                    AuditDenialReason reason, TenantPermission permission, UUID correlationId) {
                throw new IllegalStateException(DRIVER_TEXT);
            }
        });

        for (int denial = 0; denial < 12; denial++) {
            deny(identity);
        }

        verify(store, times(12)).append(any(), isNull());
        assertThat(output.getAll()).contains("Denial audit budget failed; the denial row is written",
                "exceptionClass=java.lang.IllegalStateException").doesNotContain(DRIVER_TEXT);
    }

    /** covers: AC-1 */
    @Test
    void aMissingCorrelationScopeFailsBeforeTheBudgetIsCharged() {
        assertThatThrownBy(() -> recorder.authorizationDenied(
                TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT, identity))
                .isInstanceOf(IllegalStateException.class);

        assertThat(budget.trackedWindows()).isZero();
        verifyNoInteractions(store);
    }

    /** covers: AC-1 */
    @Test
    void duringAnOutageARowInsideBudgetFailsAsUnavailableAndASuppressedDenialDoesNotTouchTheDatabase(
            CapturedOutput output) {
        for (int denial = 0; denial < 10; denial++) {
            deny(identity);
        }
        doThrow(new CannotGetJdbcConnectionException(DRIVER_TEXT)).when(store).append(any(), any());

        deny(identity);
        assertThatThrownBy(() -> deny(new IdentityId(UUID.randomUUID())))
                .isInstanceOfSatisfying(AuditPersistenceException.class,
                        failure -> assertThat(failure.databaseUnavailable()).isTrue());

        verify(store, times(11)).append(any(), any());
        assertThat(output.getAll()).containsOnlyOnce("Audit persistence failed; correlationId=")
                .containsOnlyOnce("Authorization denial rows suppressed for this window")
                .doesNotContain(DRIVER_TEXT);
    }

    /** covers: AC-1 */
    @Test
    void aFailedRowWriteStillSpendsItsUnit(CapturedOutput output) {
        doThrow(new CannotGetJdbcConnectionException(DRIVER_TEXT)).when(store).append(any(), any());

        for (int denial = 0; denial < 12; denial++) {
            try {
                deny(identity);
            } catch (AuditPersistenceException expected) {
                // Each of the ten attempts inside budget fails; none is handed back.
            }
        }

        verify(store, times(10)).append(any(), any());
        assertThat(output.getAll().split("Audit persistence failed; correlationId=", -1)).hasSize(11);
        assertThat(output.getAll()).containsOnlyOnce("Authorization denial rows suppressed for this window")
                .doesNotContain(DRIVER_TEXT);
    }

    /** covers: AC-2 */
    @Test
    void writesAnUnattributedCountUnderItsFixedRowIdWithoutACapability() {
        UUID correlation = UUID.randomUUID();
        Summary summary = new Summary(UUID.randomUUID(), null, TenantPermission.TENANT_READ,
                AuditDenialReason.NO_TENANT_CONTEXT, 990, correlation);

        assertThat(recorder.writeSuppressionSummary(summary)).isEqualTo(SummaryWrite.WRITTEN);

        ArgumentCaptor<AuditEvent> appended = ArgumentCaptor.forClass(AuditEvent.class);
        verify(store).append(appended.capture(), isNull());
        AuditEvent event = appended.getValue();
        assertThat(event.id()).isEqualTo(summary.summaryId());
        assertThat(event.type()).isEqualTo(AuditEventType.AUTHORIZATION_DENIALS_SUPPRESSED);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(event.target()).isNull();
        assertThat(event.tenantId()).isNull();
        assertThat(event.actorId()).isNull();
        assertThat(event.membershipId()).isNull();
        assertThat(event.correlationId()).isEqualTo(correlation);
        assertThat(event.timestamp()).isEqualTo(clock.instant());
        assertThat(event.metadata()).isEqualTo(new AuditMetadata.AuthorizationDenialsSuppressed(
                TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT, 990));
        verify(signer, never()).issueAuditContext(any(), any(), any());
    }

    /** covers: AC-10 */
    @Test
    void writesTheTrackerFullCountWithNoPermissionAndNoReason() {
        Summary summary = new Summary(UUID.randomUUID(), null, null, null, 7, UUID.randomUUID());

        assertThat(recorder.writeSuppressionSummary(summary)).isEqualTo(SummaryWrite.WRITTEN);

        ArgumentCaptor<AuditEvent> appended = ArgumentCaptor.forClass(AuditEvent.class);
        verify(store).append(appended.capture(), isNull());
        assertThat(appended.getValue().metadata())
                .isEqualTo(new AuditMetadata.AuthorizationDenialsSuppressed(null, null, 7));
    }

    /** covers: AC-9 */
    @Test
    void mapsEveryWriteFailureToItsResultWithoutLoggingDriverText(CapturedOutput output) {
        Summary summary = new Summary(UUID.randomUUID(), null, TenantPermission.TENANT_READ,
                AuditDenialReason.NO_TENANT_CONTEXT, 5, UUID.randomUUID());

        doThrow(new DuplicateKeyException(DRIVER_TEXT)).when(store).append(any(), any());
        assertThat(recorder.writeSuppressionSummary(summary)).isEqualTo(SummaryWrite.ALREADY_WRITTEN);
        doThrow(new CannotGetJdbcConnectionException(DRIVER_TEXT)).when(store).append(any(), any());
        assertThat(recorder.writeSuppressionSummary(summary)).isEqualTo(SummaryWrite.RETRY);
        doThrow(new QueryTimeoutException(DRIVER_TEXT)).when(store).append(any(), any());
        assertThat(recorder.writeSuppressionSummary(summary)).isEqualTo(SummaryWrite.RETRY);
        doThrow(new CannotCreateTransactionException(DRIVER_TEXT)).when(store).append(any(), any());
        assertThat(recorder.writeSuppressionSummary(summary)).isEqualTo(SummaryWrite.RETRY);
        doThrow(new DataIntegrityViolationException(DRIVER_TEXT)).when(store).append(any(), any());
        assertThat(recorder.writeSuppressionSummary(summary)).isEqualTo(SummaryWrite.REJECTED);

        assertThat(output.getAll()).contains("Denial suppression summary was not written")
                .doesNotContain(DRIVER_TEXT);
    }

    private void deny(IdentityId who) {
        execution.runWithCorrelation(UUID.randomUUID(), () -> recorder.authorizationDenied(
                TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT, who));
    }

    private TransactionalAuditRecorder recorder(DenialAuditBudget denialBudget) {
        return new TransactionalAuditRecorder(store, contexts, mock(ProviderContextProvider.class), signer,
                execution, (Clock) clock, transactions, new DatabaseUnavailability(Duration.ofSeconds(5)),
                denialBudget, new MicrometerDenialAuditTelemetry(registry, budget));
    }
}
