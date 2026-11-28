// ABOUTME: Races admissions on separate database connections against real PostgreSQL (ADR 0040, spec 0024 AC-9): the
// ABOUTME: admitted total never passes the tenant minute, tenant day, candidate day or global minute limit.
package io.github.stevdrey.dokene.admission;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.admission.AdmissionDecision.Admitted;
import io.github.stevdrey.dokene.admission.AdmissionDecision.Denied;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AdmissionBudgetContentionIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-27T12:00:00Z");
    private static final int CALLERS = 12;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        // The shared fixture pins the pool to one connection, which would line the callers up before they reach
        // the database. Each caller needs a connection of its own for the advisory locks to be what decides.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> String.valueOf(CALLERS + 2));
    }

    @Autowired AdmissionStore store;
    @Autowired TenantContextProvider contexts;
    @Autowired DatabaseUnavailability database;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TenantRepository tenants;

    private final MutableClock clock = new MutableClock(START);
    private ExecutorService callers;

    @BeforeEach
    void setUp() throws SQLException {
        clearBudgetTables();
        callers = Executors.newFixedThreadPool(CALLERS);
    }

    @AfterEach
    void tearDown() throws SQLException {
        callers.shutdownNow();
        clearBudgetTables();
    }

    @Test
    void concurrentSendsOfOneTenantNeverPassItsMinuteLimit() throws Exception {
        TenantId tenant = seedTenant("Contention Minute");
        AdmissionBudget budget = budget(sends(5, 500, 120));

        List<AdmissionDecision> decisions = race(CALLERS, caller -> tenant, budget::admitSend);

        assertThat(decisions).filteredOn(Admitted.class::isInstance).hasSize(5);
        assertThat(reasons(decisions)).containsOnly(AdmissionDenialReason.TENANT_MINUTE);
        assertThat(count("SELECT count(*) FROM dokene.tenant_budget_admissions")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM dokene.platform_send_admissions")).isEqualTo(5);
    }

    @Test
    void concurrentSendsOfOneTenantNeverPassItsDayLimit() throws Exception {
        TenantId tenant = seedTenant("Contention Day");
        AdmissionBudget budget = budget(sends(3, 6, 120));

        List<AdmissionDecision> first = race(CALLERS, caller -> tenant, budget::admitSend);
        clock.advance(Duration.ofMinutes(2));
        List<AdmissionDecision> second = race(CALLERS, caller -> tenant, budget::admitSend);
        clock.advance(Duration.ofMinutes(2));
        List<AdmissionDecision> third = race(CALLERS, caller -> tenant, budget::admitSend);

        assertThat(first).filteredOn(Admitted.class::isInstance).hasSize(3);
        assertThat(second).filteredOn(Admitted.class::isInstance).hasSize(3);
        assertThat(third).filteredOn(Admitted.class::isInstance).isEmpty();
        assertThat(reasons(third)).containsOnly(AdmissionDenialReason.TENANT_DAY);
        assertThat(count("SELECT count(*) FROM dokene.tenant_budget_admissions")).isEqualTo(6);
    }

    @Test
    void concurrentSendsOfSeveralTenantsNeverPassTheGlobalMinuteLimit() throws Exception {
        List<TenantId> several = List.of(seedTenant("Contention Global 1"), seedTenant("Contention Global 2"),
                seedTenant("Contention Global 3"), seedTenant("Contention Global 4"));
        AdmissionBudget budget = budget(sends(30, 500, 7));

        List<AdmissionDecision> decisions = race(CALLERS, caller -> several.get(caller % several.size()),
                budget::admitSend);

        assertThat(decisions).filteredOn(Admitted.class::isInstance).hasSize(7);
        assertThat(reasons(decisions)).containsOnly(AdmissionDenialReason.GLOBAL_MINUTE);
        assertThat(count("SELECT count(*) FROM dokene.platform_send_admissions")).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM dokene.tenant_budget_admissions")).isEqualTo(7);
    }

    @Test
    void concurrentCandidatesOfOneTenantNeverPassTheirDayBudget() throws Exception {
        TenantId tenant = seedTenant("Contention Candidates");
        AdmissionBudget budget = budget(new AdmissionProperties(null,
                new AdmissionProperties.Candidates(true, 4), Duration.ofSeconds(5), null));

        List<AdmissionDecision> decisions = race(CALLERS, caller -> tenant, budget::admitCandidate);

        assertThat(decisions).filteredOn(Admitted.class::isInstance).hasSize(4);
        assertThat(reasons(decisions)).containsOnly(AdmissionDenialReason.CANDIDATE_DAY);
        assertThat(count("SELECT count(*) FROM dokene.tenant_budget_admissions WHERE kind = 'CANDIDATE'"))
                .isEqualTo(4);
        assertThat(count("SELECT count(*) FROM dokene.platform_send_admissions")).isZero();
    }

    /** Every caller waits at one gate, then asks in a transaction and on a connection of its own. */
    private List<AdmissionDecision> race(int count, TenantOfCaller tenantOf, Admission admission) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AdmissionDecision>> asked = new ArrayList<>();
        for (int caller = 0; caller < count; caller++) {
            TenantId tenant = tenantOf.of(caller);
            Callable<AdmissionDecision> ask = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start the race");
                }
                return contexts.callWithTenantId(tenant, () -> new TransactionTemplate(transactionManager)
                        .execute(status -> admission.admit(tenant)));
            };
            asked.add(callers.submit(ask));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<AdmissionDecision> decisions = new ArrayList<>();
        for (Future<AdmissionDecision> answer : asked) {
            decisions.add(answer.get(30, TimeUnit.SECONDS));
        }
        return decisions;
    }

    @FunctionalInterface
    private interface TenantOfCaller {
        TenantId of(int caller);
    }

    @FunctionalInterface
    private interface Admission {
        AdmissionDecision admit(TenantId tenant);
    }

    private static List<AdmissionDenialReason> reasons(List<AdmissionDecision> decisions) {
        return decisions.stream().filter(Denied.class::isInstance).map(Denied.class::cast).map(Denied::reason)
                .toList();
    }

    private TenantId seedTenant(String name) {
        return TenantSecurityIntegrationFixture.seedTenant(tenants, contexts, name, START).id();
    }

    /** A long lock wait, so a caller that queues behind the others is counted and not timed out. */
    private static AdmissionProperties sends(int tenantPerMinute, int tenantPerDay, int globalPerMinute) {
        return new AdmissionProperties(
                new AdmissionProperties.Send(tenantPerMinute, tenantPerDay, globalPerMinute, 100, null), null,
                Duration.ofSeconds(5), null);
    }

    private AdmissionBudget budget(AdmissionProperties settings) {
        return new DefaultAdmissionBudget(settings, new AdmissionPolicy(settings), store, asked -> true, contexts,
                database, new AdmissionTelemetry(null, clock), clock);
    }

    private static int count(String sql) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            return TenantSecurityIntegrationFixture.queryInt(connection, sql);
        }
    }

    private static void clearBudgetTables() throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM dokene.tenant_budget_admissions");
            statement.execute("DELETE FROM dokene.platform_send_admissions");
            statement.execute("DELETE FROM dokene.tenant_send_pauses");
        }
    }
}
