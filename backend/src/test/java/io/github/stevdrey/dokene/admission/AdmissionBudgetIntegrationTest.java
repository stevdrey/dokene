// ABOUTME: The admission budget against real PostgreSQL as the runtime role (ADR 0040, spec 0024): rows that live and
// ABOUTME: die with the caller's transaction, window edges, pruning under row level security, isolation and fail closed.
package io.github.stevdrey.dokene.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.admission.AdmissionDecision.Admitted;
import io.github.stevdrey.dokene.admission.AdmissionDecision.Denied;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AdmissionBudgetIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-27T12:00:00Z");
    private static final Admitted ADMITTED = new Admitted();
    private static final Duration DAY = Duration.ofHours(24);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        // The throttle signal opens a transaction of its own, so one connection is not enough.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
    }

    @Autowired AdmissionBudget wiredBudget;
    @Autowired AdmissionStore store;
    @Autowired TenantContextProvider contexts;
    @Autowired DatabaseUnavailability database;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TenantRepository tenants;
    @Autowired JdbcTemplate jdbc;

    private final MutableClock clock = new MutableClock(START);
    private SendSwitch sendSwitch;
    private TenantId tenantA;
    private TenantId tenantB;

    @BeforeEach
    void setUp() throws SQLException {
        clearBudgetTables();
        sendSwitch = asked -> true;
        tenantA = TenantSecurityIntegrationFixture.seedTenant(tenants, contexts, "Admission Tenant A", START).id();
        tenantB = TenantSecurityIntegrationFixture.seedTenant(tenants, contexts, "Admission Tenant B", START).id();
    }

    @AfterEach
    void tearDown() throws SQLException {
        clearBudgetTables();
    }

    @Test
    void anAdmittedSendWritesOneTenantRowAndOnePlatformRowAtTheClocksTime() throws SQLException {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));

        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);

        assertThat(tenantRows(tenantA, "SEND")).containsExactly(START);
        assertThat(platformRows()).containsExactly(START);
    }

    @Test
    void aCallerThatRollsBackLeavesNoRowBehind() throws SQLException {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));

        AdmissionDecision decision = inTransaction(tenantA, status -> {
            AdmissionDecision admitted = budget.admitSend(tenantA);
            status.setRollbackOnly();
            return admitted;
        });

        assertThat(decision).isEqualTo(ADMITTED);
        assertThat(tenantRows(tenantA, "SEND")).isEmpty();
        assertThat(platformRows()).isEmpty();
    }

    @Test
    void aTransactionAlreadyMarkedForRollbackStillGetsANormalAnswer() throws SQLException {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));

        AdmissionDecision decision = inTransaction(tenantA, status -> {
            status.setRollbackOnly();
            return budget.admitSend(tenantA);
        });

        assertThat(decision).isEqualTo(ADMITTED);
        assertThat(platformRows()).isEmpty();
    }

    @Test
    void anAdmissionOutsideATransactionOrInAReadOnlyOneIsRefusedAsAProgrammingError() {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> contexts.callWithTenantId(tenantA, () -> budget.admitSend(tenantA)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> contexts.callWithTenantId(tenantA,
                () -> readOnly.execute(status -> budget.admitCandidate(tenantA))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theWiredBudgetDeniesASendWhileOutboundSendingIsSwitchedOffAndWritesNothing() throws SQLException {
        assertThat(store).isInstanceOf(JdbcAdmissionStore.class);

        assertThat(send(wiredBudget, tenantA))
                .isEqualTo(new Denied(AdmissionDenialReason.SWITCH_OFF, Duration.ofSeconds(1)));
        assertThat(candidate(wiredBudget, tenantA))
                .isEqualTo(new Denied(AdmissionDenialReason.SWITCH_OFF, Duration.ofSeconds(1)));
        assertThat(tenantRows(tenantA, "SEND")).isEmpty();
        assertThat(tenantRows(tenantA, "CANDIDATE")).isEmpty();
        assertThat(platformRows()).isEmpty();
    }

    @Test
    void aRowLeavesTheMinuteWindowExactlySixtySecondsAfterItWasAdmitted() throws SQLException {
        AdmissionBudget budget = budget(settings(1, 500, 120, 100));
        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);

        clock.advance(Duration.ofSeconds(20));
        assertThat(send(budget, tenantA))
                .isEqualTo(new Denied(AdmissionDenialReason.TENANT_MINUTE, Duration.ofSeconds(40)));
        clock.set(START.plusSeconds(60).minusNanos(1_000));
        assertThat(send(budget, tenantA))
                .isEqualTo(new Denied(AdmissionDenialReason.TENANT_MINUTE, Duration.ofSeconds(1)));
        clock.set(START.plusSeconds(60));
        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);

        assertThat(tenantRows(tenantA, "SEND")).containsExactly(START, START.plusSeconds(60));
    }

    @Test
    void theShareIsTheLimitAndTheReasonWhenItIsSmallerThanTheTenantsOwnLimit() {
        AdmissionBudget budget = budget(settings(30, 500, 8, 25));
        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);
        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofSeconds(15));

        assertThat(send(budget, tenantA))
                .isEqualTo(new Denied(AdmissionDenialReason.TENANT_SHARE, Duration.ofSeconds(45)));
    }

    @Test
    void theDayLimitHoldsUntilTheOldestRowIsExactlyADayOld() throws SQLException {
        AdmissionBudget budget = budget(settings(1, 2, 120, 100));
        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofHours(1));
        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);

        clock.advance(Duration.ofHours(1));
        assertThat(send(budget, tenantA))
                .isEqualTo(new Denied(AdmissionDenialReason.TENANT_DAY, Duration.ofHours(22)));
        clock.set(START.plus(DAY));
        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);

        // The row that left the window was pruned by the admission that replaced it.
        assertThat(tenantRows(tenantA, "SEND")).containsExactly(START.plus(Duration.ofHours(1)), START.plus(DAY));
    }

    @Test
    void theGlobalLimitCountsEveryTenantsAdmittedSends() throws SQLException {
        AdmissionBudget budget = budget(settings(30, 500, 2, 100));
        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofSeconds(20));
        assertThat(send(budget, tenantB)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofSeconds(20));

        assertThat(send(budget, tenantB))
                .isEqualTo(new Denied(AdmissionDenialReason.GLOBAL_MINUTE, Duration.ofSeconds(20)));
        assertThat(tenantRows(tenantB, "SEND")).hasSize(1);
        assertThat(platformRows()).hasSize(2);
        clock.advance(Duration.ofSeconds(20));
        assertThat(send(budget, tenantB)).isEqualTo(ADMITTED);
    }

    @Test
    void afterALimitWasLoweredTheDelayRunsToTheRowThatFreesOneUnit() {
        AdmissionBudget wide = budget(settings(5, 500, 120, 100));
        for (int sent = 0; sent < 5; sent++) {
            assertThat(send(wide, tenantA)).isEqualTo(ADMITTED);
            clock.advance(Duration.ofSeconds(5));
        }

        // Rows at 0, 5, 10, 15 and 20 seconds, asked at 25 with a limit of 2: the fourth row has to leave.
        assertThat(send(budget(settings(2, 500, 120, 100)), tenantA))
                .isEqualTo(new Denied(AdmissionDenialReason.TENANT_MINUTE, Duration.ofSeconds(50)));
    }

    @Test
    void aTenantAskingFarPastItsLimitLeavesTheOtherTenantsRoomUntouched() throws SQLException {
        AdmissionBudget budget = budget(settings(3, 500, 120, 100));
        int admittedForA = 0;
        for (int attempt = 0; attempt < 25; attempt++) {
            if (send(budget, tenantA) instanceof Admitted) {
                admittedForA++;
            }
        }

        assertThat(admittedForA).isEqualTo(3);
        for (int sent = 0; sent < 3; sent++) {
            assertThat(send(budget, tenantB)).isEqualTo(ADMITTED);
        }
        assertThat(send(budget, tenantB)).isInstanceOf(Denied.class);
        assertThat(platformRows()).hasSize(6);
    }

    @Test
    void candidatesHaveTheirOwnDayBudgetAndIgnoreSendsSwitchesAndThePause() throws SQLException {
        sendSwitch = asked -> false;
        AdmissionBudget budget = budget(candidates(2));
        contexts.runWithTenantId(tenantA, () -> budget.recordProviderThrottle(tenantA));

        assertThat(candidate(budget, tenantA)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofHours(2));
        assertThat(candidate(budget, tenantA)).isEqualTo(ADMITTED);
        clock.advance(Duration.ofHours(2));
        assertThat(candidate(budget, tenantA))
                .isEqualTo(new Denied(AdmissionDenialReason.CANDIDATE_DAY, Duration.ofHours(20)));

        assertThat(tenantRows(tenantA, "CANDIDATE")).containsExactly(START, START.plus(Duration.ofHours(2)));
        assertThat(tenantRows(tenantA, "SEND")).isEmpty();
        assertThat(platformRows()).isEmpty();
        assertThat(candidate(budget, tenantB)).isEqualTo(ADMITTED);
    }

    @Test
    void candidateRowsDoNotCountTowardASendLimitAndSendRowsDoNotCountTowardCandidates() throws SQLException {
        AdmissionProperties settings = new AdmissionProperties(
                new AdmissionProperties.Send(1, 1, 120, 100, null), new AdmissionProperties.Candidates(true, 1),
                null, null);
        AdmissionBudget budget = budget(settings);

        assertThat(candidate(budget, tenantA)).isEqualTo(ADMITTED);
        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);
        assertThat(candidate(budget, tenantA)).isInstanceOf(Denied.class);
        assertThat(send(budget, tenantA)).isInstanceOf(Denied.class);
        assertThat(platformRows()).hasSize(1);
    }

    @Test
    void aSendIsAdmittedWhileAnotherConnectionHoldsTheSameTenantsCandidateLock() throws SQLException {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));
        try (Connection holder = TenantSecurityIntegrationFixture.migrationConnection()) {
            holder.setAutoCommit(false);
            takeTenantLock(holder, tenantA, "CANDIDATE");

            assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);
            assertThat(candidate(budget(candidates(5)), tenantA))
                    .isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, database.retryAfter()));
            holder.rollback();
        }
    }

    @Test
    void aProviderThrottlePausesThatTenantOnlyAndANewSignalMovesTheEnd() throws SQLException {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));

        contexts.runWithTenantId(tenantA, () -> budget.recordProviderThrottle(tenantA));

        assertThat(pausedUntil(tenantA)).containsExactly(START.plusSeconds(60));
        clock.advance(Duration.ofSeconds(25));
        assertThat(send(budget, tenantA))
                .isEqualTo(new Denied(AdmissionDenialReason.TENANT_PAUSED, Duration.ofSeconds(35)));
        assertThat(send(budget, tenantB)).isEqualTo(ADMITTED);
        assertThat(tenantRows(tenantA, "SEND")).isEmpty();

        contexts.runWithTenantId(tenantA, () -> budget.recordProviderThrottle(tenantA));
        assertThat(pausedUntil(tenantA)).containsExactly(START.plusSeconds(85));
        clock.set(START.plusSeconds(85));
        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);
    }

    @Test
    void aTenantLockHeldPastTheTimeoutDeniesAndLeavesTheCallersTransactionAsItWas() throws SQLException {
        AdmissionBudget budget = budget(quickLocks());
        try (Connection holder = TenantSecurityIntegrationFixture.migrationConnection()) {
            holder.setAutoCommit(false);
            takeTenantLock(holder, tenantA, "SEND");

            String lockTimeoutAfter = inTransaction(tenantA, status -> {
                jdbc.execute("SET LOCAL lock_timeout = '7s'");
                long asked = System.nanoTime();
                assertThat(budget.admitSend(tenantA))
                        .isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, database.retryAfter()));
                assertThat(Duration.ofNanos(System.nanoTime() - asked)).isLessThan(Duration.ofSeconds(4));
                // A statement that failed outside a savepoint would have aborted this transaction.
                jdbc.update("INSERT INTO dokene.tenant_send_pauses (tenant_id, paused_until) VALUES (?, ?)",
                        tenantA.value(), Timestamp.from(START));
                return jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
            });

            assertThat(lockTimeoutAfter).isEqualTo("7s");
            assertThat(pausedUntil(tenantA)).containsExactly(START);
            holder.rollback();
        }
    }

    @Test
    void aGlobalLockHeldPastTheTimeoutDeniesAndFreesTheTenantLockAtOnce() throws SQLException {
        AdmissionBudget budget = budget(quickLocks());
        try (Connection holder = TenantSecurityIntegrationFixture.migrationConnection();
                Connection second = TenantSecurityIntegrationFixture.migrationConnection()) {
            holder.setAutoCommit(false);
            second.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(hashtext('ADMISSION_GLOBAL_SEND'), 0)");
            }

            boolean tenantLockFree = inTransaction(tenantA, status -> {
                assertThat(budget.admitSend(tenantA))
                        .isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, database.retryAfter()));
                return tryTenantLock(second, tenantA, "SEND");
            });

            assertThat(tenantLockFree).isTrue();
            assertThat(tenantRows(tenantA, "SEND")).isEmpty();
            second.rollback();
            holder.rollback();
        }
    }

    @Test
    void aDeniedCallerHoldsNoBudgetLockAndAnAdmittedOneHoldsBothToItsCommit() throws SQLException {
        AdmissionBudget budget = budget(settings(1, 500, 120, 100));
        try (Connection second = TenantSecurityIntegrationFixture.migrationConnection()) {
            second.setAutoCommit(false);

            List<Boolean> freeWhileAdmitted = inTransaction(tenantA, status -> {
                assertThat(budget.admitSend(tenantA)).isEqualTo(ADMITTED);
                return List.of(tryTenantLock(second, tenantA, "SEND"), tryGlobalLock(second));
            });
            second.rollback();
            List<Boolean> freeWhileDenied = inTransaction(tenantA, status -> {
                assertThat(budget.admitSend(tenantA)).isInstanceOf(Denied.class);
                return List.of(tryTenantLock(second, tenantA, "SEND"), tryGlobalLock(second));
            });
            second.rollback();

            assertThat(freeWhileAdmitted).containsExactly(false, false);
            assertThat(freeWhileDenied).containsExactly(true, true);
        }
    }

    @Test
    void anAdmittedCallerKeepsItsOwnLockTimeout() {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));

        String lockTimeoutAfter = inTransaction(tenantA, status -> {
            jdbc.execute("SET LOCAL lock_timeout = '7s'");
            assertThat(budget.admitSend(tenantA)).isEqualTo(ADMITTED);
            return jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
        });

        assertThat(lockTimeoutAfter).isEqualTo("7s");
    }

    @Test
    void aWriteTheRuntimeRoleMayNotMakeDeniesAndUndoesTheRowsAlreadyWritten() throws SQLException {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));
        asMigrationRole("REVOKE INSERT ON dokene.platform_send_admissions FROM dokene_runtime");
        try {
            Integer stillUsable = inTransaction(tenantA, status -> {
                assertThat(budget.admitSend(tenantA))
                        .isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, database.retryAfter()));
                return jdbc.queryForObject("SELECT 1", Integer.class);
            });

            assertThat(stillUsable).isEqualTo(1);
            assertThat(tenantRows(tenantA, "SEND")).isEmpty();
        } finally {
            asMigrationRole("GRANT INSERT ON dokene.platform_send_admissions TO dokene_runtime");
        }
        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);
    }

    @Test
    void aSwitchCheckWhoseStatementFailsDeniesWithoutPoisoningTheCallersTransaction() throws SQLException {
        sendSwitch = asked -> jdbc.queryForObject("SELECT 1 / 0", Integer.class) == 1;
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));

        Integer stillUsable = inTransaction(tenantA, status -> {
            assertThat(budget.admitSend(tenantA))
                    .isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, database.retryAfter()));
            return jdbc.queryForObject("SELECT 1", Integer.class);
        });

        assertThat(stillUsable).isEqualTo(1);
        assertThat(platformRows()).isEmpty();
    }

    @Test
    void anAdmissionDeletesItsTenantsDayOldRowsOfItsKindUpToTheBatch() throws SQLException {
        AdmissionProperties settings = new AdmissionProperties(null, new AdmissionProperties.Candidates(true, 200),
                null, 2);
        for (int old = 0; old < 5; old++) {
            insertTenantRow(tenantA, "SEND", START.minus(DAY).minusSeconds(old));
        }
        insertTenantRow(tenantA, "SEND", START.minus(DAY).plusNanos(1_000));
        insertTenantRow(tenantA, "CANDIDATE", START.minus(DAY));
        insertTenantRow(tenantB, "SEND", START.minus(DAY));

        assertThat(send(budget(settings), tenantA)).isEqualTo(ADMITTED);

        // The two oldest went; the row a microsecond inside the window, the other kind and the other tenant stayed.
        assertThat(tenantRows(tenantA, "SEND")).containsExactly(START.minus(DAY).minusSeconds(2),
                START.minus(DAY).minusSeconds(1), START.minus(DAY), START.minus(DAY).plusNanos(1_000), START);
        assertThat(tenantRows(tenantA, "CANDIDATE")).hasSize(1);
        assertThat(tenantRows(tenantB, "SEND")).hasSize(1);

        assertThat(candidate(budget(settings), tenantA)).isEqualTo(ADMITTED);
        assertThat(tenantRows(tenantA, "CANDIDATE")).containsExactly(START);
    }

    @Test
    void aSendThatReachesTheGlobalStepDeletesPlatformRowsThatLeftTheMinuteWindow() throws SQLException {
        AdmissionProperties settings = new AdmissionProperties(null, null, null, 2);
        for (int old = 0; old < 3; old++) {
            insertPlatformRow(START.minusSeconds(60 + old));
        }
        insertPlatformRow(START.minusSeconds(59));

        assertThat(send(budget(settings), tenantA)).isEqualTo(ADMITTED);

        assertThat(platformRows()).containsExactly(START.minusSeconds(60), START.minusSeconds(59), START);
    }

    @Test
    void theTablesHoldNoIdentifierBeyondTheTenantAndTheRowsOwnId() throws SQLException {
        assertThat(columns("tenant_budget_admissions")).containsExactly("id", "tenant_id", "kind", "admitted_at");
        assertThat(columns("platform_send_admissions")).containsExactly("id", "admitted_at");
        assertThat(columns("tenant_send_pauses")).containsExactly("tenant_id", "paused_until");
    }

    @Test
    void oneTenantsContextCanNeitherReadCountInsertNorDeleteAnotherTenantsRows() throws SQLException {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));
        assertThat(send(budget, tenantA)).isEqualTo(ADMITTED);
        contexts.runWithTenantId(tenantA, () -> budget.recordProviderThrottle(tenantA));

        contexts.runWithTenantId(tenantB, () -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dokene.tenant_budget_admissions", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dokene.tenant_send_pauses", Integer.class))
                    .isZero();
            assertThat(jdbc.update("DELETE FROM dokene.tenant_budget_admissions")).isZero();
            assertThat(jdbc.update("UPDATE dokene.tenant_send_pauses SET paused_until = ?", Timestamp.from(START)))
                    .isZero();
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO dokene.tenant_budget_admissions (tenant_id, kind, admitted_at) VALUES (?, 'SEND', ?)",
                    tenantA.value(), Timestamp.from(START))).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO dokene.tenant_send_pauses (tenant_id, paused_until) VALUES (?, ?)",
                    tenantA.value(), Timestamp.from(START))).isInstanceOf(DataAccessException.class);
        });

        assertThat(tenantRows(tenantA, "SEND")).containsExactly(START);
        assertThat(pausedUntil(tenantA)).containsExactly(START.plusSeconds(60));
    }

    @Test
    void anAdmissionForATenantThatIsNotTheContextTenantIsDeniedAndWritesNothing() throws SQLException {
        AdmissionBudget budget = budget(settings(30, 500, 120, 25));

        AdmissionDecision crossed = contexts.callWithTenantId(tenantB,
                () -> new TransactionTemplate(transactionManager).execute(status -> budget.admitSend(tenantA)));

        assertThat(crossed).isEqualTo(new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, database.retryAfter()));
        assertThat(tenantRows(tenantA, "SEND")).isEmpty();
        assertThat(tenantRows(tenantB, "SEND")).isEmpty();
        assertThat(platformRows()).isEmpty();
    }

    private AdmissionBudget budget(AdmissionProperties settings) {
        return new DefaultAdmissionBudget(settings, new AdmissionPolicy(settings), store,
                asked -> sendSwitch.sendingAllowed(asked), contexts, database, new AdmissionTelemetry(null, clock),
                clock);
    }

    private static AdmissionProperties settings(int tenantPerMinute, int tenantPerDay, int globalPerMinute,
            int sharePercent) {
        return new AdmissionProperties(
                new AdmissionProperties.Send(tenantPerMinute, tenantPerDay, globalPerMinute, sharePercent, null),
                null, null, null);
    }

    private static AdmissionProperties candidates(int tenantPerDay) {
        return new AdmissionProperties(null, new AdmissionProperties.Candidates(true, tenantPerDay),
                Duration.ofMillis(200), null);
    }

    private static AdmissionProperties quickLocks() {
        return new AdmissionProperties(null, null, Duration.ofMillis(200), null);
    }

    private AdmissionDecision send(AdmissionBudget budget, TenantId tenant) {
        return inTransaction(tenant, status -> budget.admitSend(tenant));
    }

    private AdmissionDecision candidate(AdmissionBudget budget, TenantId tenant) {
        return inTransaction(tenant, status -> budget.admitCandidate(tenant));
    }

    private <T> T inTransaction(TenantId tenant, TransactionWork<T> work) {
        Supplier<T> transaction = () -> new TransactionTemplate(transactionManager).execute(status -> {
            try {
                return work.run(status);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
        return contexts.callWithTenantId(tenant, transaction::get);
    }

    @FunctionalInterface
    private interface TransactionWork<T> {
        T run(TransactionStatus status) throws SQLException;
    }

    private static void takeTenantLock(Connection connection, TenantId tenant, String kind) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))")) {
            lock.setString(1, tenant.value() + ":ADMISSION_" + kind);
            lock.setString(2, tenant.value().toString());
            lock.execute();
        }
    }

    private static boolean tryTenantLock(Connection connection, TenantId tenant, String kind) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT pg_try_advisory_xact_lock(hashtext(?), hashtext(?))")) {
            lock.setString(1, tenant.value() + ":ADMISSION_" + kind);
            lock.setString(2, tenant.value().toString());
            try (ResultSet row = lock.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        }
    }

    private static boolean tryGlobalLock(Connection connection) throws SQLException {
        try (Statement lock = connection.createStatement(); ResultSet row = lock.executeQuery(
                "SELECT pg_try_advisory_xact_lock(hashtext('ADMISSION_GLOBAL_SEND'), 0)")) {
            row.next();
            return row.getBoolean(1);
        }
    }

    private static List<Instant> tenantRows(TenantId tenant, String kind) throws SQLException {
        return instants("SELECT admitted_at FROM dokene.tenant_budget_admissions WHERE tenant_id = ? AND kind = ? "
                + "ORDER BY admitted_at, id", tenant.value(), kind);
    }

    private static List<Instant> platformRows() throws SQLException {
        return instants("SELECT admitted_at FROM dokene.platform_send_admissions ORDER BY admitted_at, id");
    }

    private static List<Instant> pausedUntil(TenantId tenant) throws SQLException {
        return instants("SELECT paused_until FROM dokene.tenant_send_pauses WHERE tenant_id = ?", tenant.value());
    }

    /** Read as the migration role, which sees every tenant's rows. */
    private static List<Instant> instants(String sql, Object... parameters) throws SQLException {
        List<Instant> values = new ArrayList<>();
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getTimestamp(1).toInstant());
                }
            }
        }
        return values;
    }

    private static List<String> columns(String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT column_name FROM "
                        + "information_schema.columns WHERE table_schema = 'dokene' AND table_name = ? "
                        + "ORDER BY ordinal_position")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
        }
        return names;
    }

    private static void insertTenantRow(TenantId tenant, String kind, Instant admittedAt) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            TenantSecurityIntegrationFixture.executeUpdate(connection, "INSERT INTO dokene.tenant_budget_admissions "
                    + "(tenant_id, kind, admitted_at) VALUES (?, ?, ?)", tenant.value(), kind,
                    Timestamp.from(admittedAt));
        }
    }

    private static void insertPlatformRow(Instant admittedAt) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            TenantSecurityIntegrationFixture.executeUpdate(connection,
                    "INSERT INTO dokene.platform_send_admissions (admitted_at) VALUES (?)",
                    Timestamp.from(admittedAt));
        }
    }

    private static void asMigrationRole(String sql) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void clearBudgetTables() throws SQLException {
        asMigrationRole("DELETE FROM dokene.tenant_budget_admissions");
        asMigrationRole("DELETE FROM dokene.platform_send_admissions");
        asMigrationRole("DELETE FROM dokene.tenant_send_pauses");
    }
}
