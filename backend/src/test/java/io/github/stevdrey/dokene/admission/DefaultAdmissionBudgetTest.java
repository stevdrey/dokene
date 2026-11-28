// ABOUTME: The admission budget over an in memory store (ADR 0040, spec 0024): the fixed order of the steps, each
// ABOUTME: denial reason with its retry delay, the switches, the tenant check, fail closed mapping and the pause.
package io.github.stevdrey.dokene.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.stevdrey.dokene.admission.AdmissionDecision.Admitted;
import io.github.stevdrey.dokene.admission.AdmissionDecision.Denied;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.tenant.application.ScopedValueTenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DefaultAdmissionBudgetTest {
    private static final Instant START = Instant.parse("2026-11-27T12:00:00Z");
    private static final Duration DATABASE_RETRY = Duration.ofSeconds(5);
    private static final Admitted ADMITTED = new Admitted();

    private final TenantId tenant = TenantId.random();
    private final TenantId other = TenantId.random();
    private final MutableClock clock = new MutableClock(START);
    private final InMemoryAdmissionStore store = new InMemoryAdmissionStore();
    private final TenantContextProvider contexts = new ScopedValueTenantContextProvider();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AdmissionTelemetry.class);
    private SendSwitch sendSwitch = asked -> true;

    @BeforeEach
    void openATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        // The denials and failed signals below are expected, so their lines are captured here and asserted.
        logs.start();
        logger.addAppender(logs);
        logger.setAdditive(false);
    }

    @AfterEach
    void closeTheTransaction() {
        logger.detachAppender(logs);
        logger.setAdditive(true);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    void anAdmittedSendRunsTheStepsInTheirFixedOrderAndWritesOneRowOfEach() {
        AdmissionDecision decision = send(budget(settings(30, 500, 120, 25)), tenant);

        assertThat(decision).isEqualTo(ADMITTED);
        assertThat(store.calls).containsExactly("boundLockWaits", "lockTenant:SEND", "pausedUntil",
                "pruneTenant:SEND", "tenantUsage:SEND", "tenantUsage:SEND", "lockGlobalSends", "prunePlatform",
                "platformUsage", "insertTenant:SEND", "insertPlatform");
        assertThat(store.lockWaitsBoundTo).isEqualTo(Duration.ofSeconds(1));
        assertThat(store.rows(tenant, AdmissionKind.SEND)).containsExactly(START);
        assertThat(store.platformRows).containsExactly(START);
        assertThat(decisions("SEND", "ADMITTED", "NONE")).isEqualTo(1.0);
    }

    @Test
    void theClockIsReadAfterTheTenantLockAndAgainAfterTheGlobalLock() {
        store.onTenantLock = () -> clock.advance(Duration.ofSeconds(5));
        store.onGlobalLock = () -> clock.advance(Duration.ofSeconds(3));

        send(budget(settings(30, 500, 120, 25)), tenant);

        assertThat(store.lastTenantPruneCutoff).isEqualTo(START.plusSeconds(5).minus(Duration.ofHours(24)));
        assertThat(store.lastPlatformPruneCutoff).isEqualTo(START.plusSeconds(8).minusSeconds(60));
        assertThat(store.rows(tenant, AdmissionKind.SEND)).containsExactly(START.plusSeconds(8));
        assertThat(store.platformRows).containsExactly(START.plusSeconds(8));
    }

    @Test
    void theTenantMinuteLimitDeniesUntilTheOldestRowLeavesTheWindow() {
        AdmissionBudget budget = budget(settings(2, 500, 120, 100));
        assertThat(send(budget, tenant)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofSeconds(10));
        assertThat(send(budget, tenant)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofSeconds(10));
        store.calls.clear();

        assertThat(send(budget, tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.TENANT_MINUTE, Duration.ofSeconds(40)));
        assertThat(store.rows(tenant, AdmissionKind.SEND)).hasSize(2);
        assertThat(store.platformRows).hasSize(2);
        assertThat(store.calls).doesNotContain("lockGlobalSends").endsWith("tenantUsage:SEND");
        assertThat(logLines()).containsExactly("Admission denied; kind=SEND, reason=TENANT_MINUTE, suppressed=0");
        assertThat(logLines()).noneMatch(line -> line.contains(tenant.value().toString()));
        assertThat(decisions("SEND", "DENIED", "TENANT_MINUTE")).isEqualTo(1.0);
    }

    @Test
    void aRowExactlySixtySecondsOldNoLongerCounts() {
        AdmissionBudget budget = budget(settings(1, 500, 120, 100));
        assertThat(send(budget, tenant)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofSeconds(60).minusMillis(1));
        assertThat(send(budget, tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.TENANT_MINUTE, Duration.ofSeconds(1)));
        clock.advance(Duration.ofMillis(1));

        assertThat(send(budget, tenant)).isEqualTo(ADMITTED);
    }

    @Test
    void theShareDeniesWithItsOwnReasonWhenItIsTheSmallerLimit() {
        AdmissionBudget budget = budget(settings(30, 500, 8, 25));
        assertThat(send(budget, tenant)).isEqualTo(ADMITTED);
        assertThat(send(budget, tenant)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofSeconds(15));

        assertThat(send(budget, tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.TENANT_SHARE, Duration.ofSeconds(45)));
    }

    @Test
    void theTenantDayLimitDeniesUntilTheOldestRowOfTheDayLeaves() {
        AdmissionBudget budget = budget(settings(1, 2, 120, 100));
        assertThat(send(budget, tenant)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofHours(1));
        assertThat(send(budget, tenant)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofHours(1));

        assertThat(send(budget, tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.TENANT_DAY, Duration.ofHours(22)));
        clock.advance(Duration.ofHours(22));
        assertThat(send(budget, tenant)).isEqualTo(ADMITTED);
        assertThat(store.rows(tenant, AdmissionKind.SEND)).hasSize(2);
    }

    @Test
    void theGlobalMinuteLimitDeniesEveryTenantAndCountsNothing() {
        AdmissionBudget budget = budget(settings(30, 500, 2, 100));
        assertThat(send(budget, tenant)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofSeconds(20));
        assertThat(send(budget, other)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofSeconds(20));

        assertThat(send(budget, other))
                .isEqualTo(new Denied(AdmissionDenialReason.GLOBAL_MINUTE, Duration.ofSeconds(20)));
        assertThat(store.rows(other, AdmissionKind.SEND)).hasSize(1);
        assertThat(store.platformRows).hasSize(2);
    }

    @Test
    void aNoisyTenantDoesNotTakeAnotherTenantsRoom() {
        AdmissionBudget budget = budget(settings(2, 500, 120, 100));
        for (int attempt = 0; attempt < 50; attempt++) {
            send(budget, tenant);
        }

        assertThat(send(budget, other)).isEqualTo(ADMITTED);
        assertThat(send(budget, other)).isEqualTo(ADMITTED);
        assertThat(store.rows(tenant, AdmissionKind.SEND)).hasSize(2);
        assertThat(store.platformRows).hasSize(4);
    }

    @Test
    void aSendWithTheSwitchOffTakesNoLockAndTouchesNoBudgetState() {
        sendSwitch = asked -> false;

        assertThat(send(budget(settings(30, 500, 120, 25)), tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.SWITCH_OFF, Duration.ofSeconds(1)));
        assertThat(store.calls).isEmpty();
    }

    @Test
    void aSwitchCheckThatThrowsAnswersStateUnavailable() {
        sendSwitch = asked -> {
            throw new IllegalStateException("switch state missing");
        };

        assertThat(send(budget(settings(30, 500, 120, 25)), tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, DATABASE_RETRY));
        assertThat(store.calls).isEmpty();
        assertThat(decisions("SEND", "DENIED", "STATE_UNAVAILABLE")).isEqualTo(1.0);
    }

    @Test
    void aTenantArgumentThatIsNotTheContextTenantIsDeniedBeforeAnythingIsTouched() {
        sendSwitch = asked -> {
            throw new AssertionError("the switch must not be asked");
        };
        AdmissionBudget budget = budget(settings(30, 500, 120, 25), new UntouchableStore());

        assertThat(contexts.callWithTenantId(other, () -> budget.admitSend(tenant)))
                .isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, DATABASE_RETRY));
        assertThat(contexts.callWithTenantId(other, () -> budget.admitCandidate(tenant)))
                .isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, DATABASE_RETRY));
        assertThat(budget.admitSend(tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, DATABASE_RETRY));
        assertThat(budget.admitCandidate(tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, DATABASE_RETRY));
    }

    @Test
    void anAdmissionWithoutATransactionOrInAReadOnlyOneIsAProgrammingError() {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25), new UntouchableStore());
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> send(budget, tenant)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> candidate(budget, tenant)).isInstanceOf(IllegalStateException.class);

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> send(budget, tenant)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> candidate(budget, tenant)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aLockThatCannotBeTakenInTimeAnswersStateUnavailableWithTheDatabaseDelay() {
        store.lockFailure = new SQLException("canceling statement due to lock timeout", "55P03");
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));

        assertThat(send(budget, tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, DATABASE_RETRY));
        assertThat(candidate(budget(candidates(5)), tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, DATABASE_RETRY));
        assertThat(store.rows(tenant, AdmissionKind.SEND)).isEmpty();
    }

    @Test
    void aLostDatabaseConnectionIsRaisedAndNotTurnedIntoADenial() {
        store.lockFailure = new SQLException("connection lost", "08006");
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));

        assertThatThrownBy(() -> send(budget, tenant)).hasRootCauseInstanceOf(SQLException.class);
        assertThat(meters.find(AdmissionTelemetry.DECISIONS).counters()).isEmpty();
    }

    @Test
    void aCandidateIsCountedAgainstItsOwnDayBudgetOnly() {
        sendSwitch = asked -> {
            throw new AssertionError("a candidate admission ignores the outbound switches");
        };
        store.pauses.put(tenant, START.plus(Duration.ofHours(1)));
        store.rows(tenant, AdmissionKind.SEND).addAll(List.of(START, START, START));
        AdmissionBudget budget = budget(candidates(2));

        assertThat(candidate(budget, tenant)).isEqualTo(ADMITTED);
        assertThat(store.calls).containsExactly("boundLockWaits", "lockTenant:CANDIDATE", "pruneTenant:CANDIDATE",
                "tenantUsage:CANDIDATE", "insertTenant:CANDIDATE");
        clock.advance(Duration.ofHours(3));
        assertThat(candidate(budget, tenant)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofHours(1));

        assertThat(candidate(budget, tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.CANDIDATE_DAY, Duration.ofHours(20)));
        assertThat(store.rows(tenant, AdmissionKind.CANDIDATE)).hasSize(2);
        assertThat(store.rows(tenant, AdmissionKind.SEND)).hasSize(3);
        assertThat(store.platformRows).isEmpty();
        assertThat(decisions("CANDIDATE", "ADMITTED", "NONE")).isEqualTo(2.0);
        assertThat(decisions("CANDIDATE", "DENIED", "CANDIDATE_DAY")).isEqualTo(1.0);
    }

    @Test
    void candidateRowsNeverCountTowardASendLimit() {
        store.rows(tenant, AdmissionKind.CANDIDATE).addAll(List.of(START, START, START));

        assertThat(send(budget(settings(1, 1, 120, 100)), tenant)).isEqualTo(ADMITTED);
    }

    @Test
    void aCandidateWhileCandidateAdmissionIsOffTouchesNoBudgetState() {
        assertThat(candidate(budget(settings(30, 500, 120, 25)), tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.SWITCH_OFF, Duration.ofSeconds(1)));
        assertThat(store.calls).isEmpty();
    }

    @Test
    void aProviderThrottlePausesOnlyThatTenantForTheConfiguredTime() {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));

        contexts.runWithTenantId(tenant, () -> budget.recordProviderThrottle(tenant));

        assertThat(store.pauses).containsEntry(tenant, START.plusSeconds(60));
        clock.advance(Duration.ofSeconds(25));
        assertThat(send(budget, tenant))
                .isEqualTo(new Denied(AdmissionDenialReason.TENANT_PAUSED, Duration.ofSeconds(35)));
        assertThat(store.calls).endsWith("pausedUntil");
        assertThat(send(budget, other)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofSeconds(35));
        assertThat(send(budget, tenant)).isEqualTo(ADMITTED);
    }

    @Test
    void aNewThrottleSignalMovesTheEndOfThePauseAndNeverStacksOnIt() {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));
        contexts.runWithTenantId(tenant, () -> budget.recordProviderThrottle(tenant));
        clock.advance(Duration.ofSeconds(40));

        contexts.runWithTenantId(tenant, () -> budget.recordProviderThrottle(tenant));

        assertThat(store.pauses).containsEntry(tenant, START.plusSeconds(100));
    }

    @Test
    void aThrottleSignalThatCannotBeStoredIsCountedAndNeverRaised() {
        store.pauseFailure = new IllegalStateException("no connection");
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));

        contexts.runWithTenantId(tenant, () -> budget.recordProviderThrottle(tenant));

        assertThat(store.pauses).isEmpty();
        assertThat(throttleFailures()).isEqualTo(1.0);
        assertThat(logLines()).containsExactly(
                "Admission throttle signal not recorded; errorType=IllegalStateException");
    }

    @Test
    void aThrottleSignalForATenantThatIsNotInContextIsCountedAndNotStored() {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));

        contexts.runWithTenantId(other, () -> budget.recordProviderThrottle(tenant));
        budget.recordProviderThrottle(tenant);

        assertThat(store.calls).isEmpty();
        assertThat(throttleFailures()).isEqualTo(2.0);
        assertThat(logLines()).containsExactly("Admission throttle signal not recorded; errorType=TenantMismatch",
                "Admission throttle signal not recorded; errorType=TenantMismatch");
    }

    private AdmissionDecision send(AdmissionBudget budget, TenantId asked) {
        return contexts.callWithTenantId(asked, () -> budget.admitSend(asked));
    }

    private AdmissionDecision candidate(AdmissionBudget budget, TenantId asked) {
        return contexts.callWithTenantId(asked, () -> budget.admitCandidate(asked));
    }

    private AdmissionBudget budget(AdmissionProperties settings) {
        return budget(settings, store);
    }

    private AdmissionBudget budget(AdmissionProperties settings, AdmissionStore backing) {
        return new DefaultAdmissionBudget(settings, new AdmissionPolicy(settings), backing,
                asked -> sendSwitch.sendingAllowed(asked), contexts, new DatabaseUnavailability(DATABASE_RETRY),
                new AdmissionTelemetry(meters, clock), clock);
    }

    private static AdmissionProperties settings(int tenantPerMinute, int tenantPerDay, int globalPerMinute,
            int sharePercent) {
        return new AdmissionProperties(
                new AdmissionProperties.Send(tenantPerMinute, tenantPerDay, globalPerMinute, sharePercent, null),
                null, null, null);
    }

    private static AdmissionProperties candidates(int tenantPerDay) {
        return new AdmissionProperties(null, new AdmissionProperties.Candidates(true, tenantPerDay), null, null);
    }

    private double decisions(String kind, String outcome, String reason) {
        return meters.counter(AdmissionTelemetry.DECISIONS, "kind", kind, "outcome", outcome, "reason", reason)
                .count();
    }

    private List<String> logLines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private double throttleFailures() {
        return meters.counter(AdmissionTelemetry.STORE_FAILURES, "operation", AdmissionTelemetry.THROTTLE_SIGNAL)
                .count();
    }

    /** Proves a path decides before any lock, read or write: every store call fails the test. */
    private static final class UntouchableStore implements AdmissionStore {
        @Override
        public AdmissionDecision underSavepoint(SavepointWork work) {
            throw new AssertionError("the store must not be touched");
        }

        @Override
        public void pause(TenantId tenant, Instant until) {
            throw new AssertionError("the store must not be touched");
        }
    }
}
