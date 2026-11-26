package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateGeneration;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateGenerator;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateProperties;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateRepository;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.domain.FollowUpCandidate;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Issue 460 (ADR 0036): candidate generation against PostgreSQL with forced RLS and the runtime role. */
@SpringBootTest(properties = "dokene.ai.provider=fake")
class FollowUpCandidateGenerationIntegrationTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
    }

    @Autowired FollowUpCandidateRepository candidates;
    @Autowired FollowUpService followUps;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired CustomerService customers;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private Tenant tenantA;
    private Tenant tenantB;
    private TenantContext contextA;
    private TenantContext contextB;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, contexts, "Candidates A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, contexts, "Candidates B " + UUID.randomUUID(), now);
        contextA = context(seedMembership(memberships, contexts, tenantA.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
        contextB = context(seedMembership(memberships, contexts, tenantB.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
    }

    @Test
    void usesTheTenantCalendarAcrossADaylightSavingTransition() throws Exception {
        inContext(contextA, () -> followUps.configureTenant(1, NEW_YORK, 0));
        Customer customer = consentedCustomer(contextA, "DST customer");
        // 23:30 EST on 2026-03-07; New York moves to EDT at 07:00Z on 2026-03-08. With a one-day cadence the cycle is
        // due on the local 2026-03-08, which starts at 05:00Z and ends at 03:59:59Z the next day (a 23-hour day).
        inContext(contextA, () -> purchases.record(customer.id(), Instant.parse("2026-03-08T04:30:00Z"),
                "DST anchor", "dst-" + UUID.randomUUID()));

        assertThat(generate(tenantA, "2026-03-08T04:59:59Z").recorded()).isEmpty();

        FollowUpCandidateGeneration first = generate(tenantA, "2026-03-08T05:00:00Z");
        assertThat(first.recorded()).singleElement().satisfies(candidate -> {
            assertThat(candidate.customerId()).isEqualTo(customer.id());
            assertThat(candidate.dueDate()).isEqualTo(LocalDate.parse("2026-03-08"));
            assertThat(candidate.generatedOn()).isEqualTo(LocalDate.parse("2026-03-08"));
            assertThat(candidate.timingSource()).isEqualTo(FollowUpTimingSource.LAST_PURCHASE);
            assertThat(candidate.effectiveCadenceDays()).isEqualTo(1);
        });

        // The rest of the local day after the jump, and the next local day: the same cycle, never a second candidate.
        assertThat(generate(tenantA, "2026-03-09T03:59:59Z").recorded()).isEmpty();
        assertThat(generate(tenantA, "2026-03-09T04:00:00Z").recorded()).isEmpty();
        assertThat(stored(tenantA)).extracting(FollowUpCandidate::id).containsExactly(first.recorded().getFirst().id());
    }

    @Test
    void repeatedScansRecordEachCycleOnceAndANewCycleGetsItsOwnCandidate() throws Exception {
        Customer customer = consentedCustomer(contextA, "Cycle customer");
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        var policy = inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today.minusDays(3), 0));
        long versionBefore = policy.version();

        FollowUpCandidateGeneration first = generate(tenantA, now);
        assertThat(first.recorded()).singleElement().satisfies(candidate -> {
            assertThat(candidate.dueDate()).isEqualTo(today.minusDays(3));
            assertThat(candidate.timingSource()).isEqualTo(FollowUpTimingSource.EXPLICIT_DATE);
            assertThat(candidate.policyVersion()).isEqualTo(versionBefore);
        });
        for (int scan = 0; scan < 3; scan++) {
            assertThat(generate(tenantA, now).recorded()).isEmpty();
        }
        // Generation neither advances the cadence nor changes the policy, and the customer stays in the due queue.
        var unchanged = inContext(contextA, () -> followUps.customerPolicy(customer.id()));
        assertThat(unchanged.version()).isEqualTo(versionBefore);
        assertThat(unchanged.explicitNextDate()).isEqualTo(today.minusDays(3));
        assertThat(inContext(contextA, () -> followUps.evaluate(customer.id())).nextFollowUpDate())
                .isEqualTo(today.minusDays(3));
        assertThat(countRows("SELECT count(*) FROM dokene.outbound_messages WHERE tenant_id = ?", tenantA.id()))
                .isZero();

        inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today, versionBefore));
        assertThat(generate(tenantA, now).recorded()).singleElement()
                .satisfies(candidate -> assertThat(candidate.dueDate()).isEqualTo(today));
        assertThat(stored(tenantA)).extracting(FollowUpCandidate::dueDate)
                .containsExactly(today.minusDays(3), today);
    }

    @Test
    void aSnoozeDefersTheCycleWithoutStartingANewOne() throws Exception {
        Customer customer = consentedCustomer(contextA, "Snoozed customer");
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        var policy = inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today.minusDays(1), 0));
        inContext(contextA, () -> followUps.snooze(customer.id(), today.plusDays(1), policy.version()));

        assertThat(generate(tenantA, now).recorded()).isEmpty();
        assertThat(generate(tenantA, now.plus(Duration.ofDays(1))).recorded()).isEmpty();
        FollowUpCandidateGeneration afterSnooze = generate(tenantA, now.plus(Duration.ofDays(2)));
        assertThat(afterSnooze.recorded()).singleElement()
                .satisfies(candidate -> assertThat(candidate.dueDate()).isEqualTo(today.minusDays(1)));
    }

    @Test
    void skipsArchivedDoNotContactUnconsentedAndNotYetDueCustomers() throws Exception {
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Customer due = consentedCustomer(contextA, "Due customer");
        Customer unconsented = customer(contextA, "Unconsented customer");
        Customer doNotContact = consentedCustomer(contextA, "Do-not-contact customer");
        Customer archived = consentedCustomer(contextA, "Archived customer");
        Customer notYetDue = consentedCustomer(contextA, "Future customer");
        for (Customer customer : List.of(due, unconsented, doNotContact, archived)) {
            inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today, 0));
        }
        inContext(contextA, () -> followUps.configureCustomer(notYetDue.id(), 7, today.plusDays(1), 0));
        var contactPolicy = inContext(contextA, () -> contacts.get(doNotContact.id()));
        inContext(contextA, () -> contacts.changeDoNotContact(doNotContact.id(), true,
                ContactIntentSource.CUSTOMER_VERBAL, contactPolicy.version()));
        long archivedVersion = inContext(contextA, () -> customers.get(archived.id())).version();
        inContext(contextA, () -> {
            customers.archive(archived.id(), archivedVersion);
            return null;
        });

        assertThat(generate(tenantA, now).recorded()).extracting(FollowUpCandidate::customerId)
                .containsExactly(due.id());
    }

    @Test
    void aFullBatchLeavesTheRestForTheNextScanInDueOrder() throws Exception {
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<CustomerId> expected = new ArrayList<>();
        for (int days = 3; days >= 1; days--) {
            Customer customer = consentedCustomer(contextA, "Batch customer " + days);
            int offset = days;
            inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today.minusDays(offset), 0));
            expected.add(customer.id());
        }
        FollowUpCandidateGenerator generator = generator(now, 2);

        FollowUpCandidateGeneration first = generator.generate(tenantA.id());
        assertThat(first.batchFull()).isTrue();
        assertThat(first.recorded()).extracting(FollowUpCandidate::customerId)
                .containsExactly(expected.get(0), expected.get(1));
        FollowUpCandidateGeneration second = generator.generate(tenantA.id());
        assertThat(second.batchFull()).isFalse();
        assertThat(second.recorded()).extracting(FollowUpCandidate::customerId).containsExactly(expected.get(2));
        assertThat(generator.generate(tenantA.id()).recorded()).isEmpty();
    }

    @Test
    void concurrentScansTogetherRecordEachCycleOnce() throws Exception {
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        for (int index = 0; index < 3; index++) {
            Customer customer = consentedCustomer(contextA, "Concurrent customer " + index);
            inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today, 0));
        }
        FollowUpCandidateGenerator generator = generator(now, 100);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> scans = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            scans.add(() -> {
                start.await(10, TimeUnit.SECONDS);
                return generator.generate(tenantA.id()).recorded().size();
            });
        }
        int recorded = 0;
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Future<Integer>> futures = scans.stream().map(executor::submit).toList();
            start.countDown();
            for (Future<Integer> future : futures) {
                recorded += future.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(recorded).isEqualTo(3);
        assertThat(stored(tenantA)).hasSize(3);
    }

    @Test
    void aScanWaitsForAnUncommittedInsertOfTheSameCycleAndThenSkipsIt() throws Exception {
        Customer customer = consentedCustomer(contextA, "Racing customer");
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today, 0));
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> holder = executor.submit(() -> contexts.callWithTenantId(tenantA.id(),
                    () -> transaction.execute(status -> {
                        int count = candidates.recordDueCandidates(tenantA.id(), now, 100).size();
                        inserted.countDown();
                        await(release);
                        return count;
                    })));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<FollowUpCandidateGeneration> racer = executor.submit(
                    () -> generator(now, 100).generate(tenantA.id()));
            awaitLockWaiter();
            release.countDown();

            assertThat(holder.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(racer.get(30, TimeUnit.SECONDS).recorded()).isEmpty();
        }
        assertThat(stored(tenantA)).hasSize(1);
    }

    @Test
    void scansAndReadsStayInsideTheirTenant() throws Exception {
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Customer customerA = consentedCustomer(contextA, "Tenant A customer");
        Customer customerB = consentedCustomer(contextB, "Tenant B customer");
        inContext(contextA, () -> followUps.configureCustomer(customerA.id(), 7, today, 0));
        inContext(contextB, () -> followUps.configureCustomer(customerB.id(), 7, today, 0));

        assertThat(generate(tenantA, now).recorded()).extracting(FollowUpCandidate::customerId)
                .containsExactly(customerA.id());
        assertThat(stored(tenantB)).isEmpty();
        // Under tenant B's scope, RLS hides tenant A's rows even when tenant A's id is asked for.
        assertThat(contexts.callWithTenantId(tenantB.id(), () -> candidates.findCandidates(tenantA.id(), 10)))
                .isEmpty();
        assertThat(generate(tenantB, now).recorded()).extracting(FollowUpCandidate::customerId)
                .containsExactly(customerB.id());
        assertThat(stored(tenantA)).extracting(FollowUpCandidate::customerId).containsExactly(customerA.id());

        // A row for another tenant is refused by the insert policy.
        assertThatThrownBy(() -> contexts.callWithTenantId(tenantB.id(), () -> jdbc.update("""
                INSERT INTO dokene.follow_up_candidates
                    (id, tenant_id, customer_id, due_date, timing_source, effective_cadence_days, policy_version,
                     generated_on, generated_at)
                VALUES (gen_random_uuid(), ?, ?, ?, 'EXPLICIT_DATE', 7, 1, ?, now())
                """, tenantA.id().value(), customerA.id().value(), java.sql.Date.valueOf(today.minusDays(1)),
                java.sql.Date.valueOf(today)))).isInstanceOf(RuntimeException.class);
        // The runtime role can neither rewrite nor remove a candidate.
        assertThatThrownBy(() -> contexts.callWithTenantId(tenantA.id(), () -> jdbc.update(
                "DELETE FROM dokene.follow_up_candidates WHERE tenant_id = ?", tenantA.id().value())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> contexts.callWithTenantId(tenantA.id(), () -> jdbc.update(
                "UPDATE dokene.follow_up_candidates SET due_date = due_date WHERE tenant_id = ?",
                tenantA.id().value()))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void recordsNothingForATenantThatIsNoLongerActive() throws Exception {
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Customer customer = consentedCustomer(contextA, "Suspended tenant customer");
        inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today, 0));
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            TenantSecurityIntegrationFixture.executeUpdate(connection,
                    "UPDATE dokene.tenants SET status = 'SUSPENDED' WHERE id = ?", tenantA.id().value());
        }

        // The insert re-checks the tenant itself, so a suspension after the generator's check still stops it.
        List<FollowUpCandidate> recorded = contexts.callWithTenantId(tenantA.id(),
                () -> new TransactionTemplate(transactionManager)
                        .execute(status -> candidates.recordDueCandidates(tenantA.id(), now, 100)));
        assertThat(recorded).isEmpty();
        assertThat(generate(tenantA, now).outcome()).isEqualTo(FollowUpCandidateGeneration.Outcome.TENANT_INACTIVE);
        assertThat(stored(tenantA)).isEmpty();
        assertThatThrownBy(() -> contexts.callWithTenantId(tenantA.id(),
                () -> candidates.findCandidates(tenantA.id(), 0)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private FollowUpCandidateGeneration generate(Tenant tenant, String instant) {
        return generate(tenant, Instant.parse(instant));
    }

    private FollowUpCandidateGeneration generate(Tenant tenant, Instant instant) {
        return generator(instant, 100).generate(tenant.id());
    }

    private FollowUpCandidateGenerator generator(Instant instant, int batchSize) {
        return new FollowUpCandidateGenerator(candidates, tenants, contexts,
                new FollowUpCandidateProperties(batchSize), transactionManager, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private List<FollowUpCandidate> stored(Tenant tenant) {
        return contexts.callWithTenantId(tenant.id(), () -> candidates.findCandidates(tenant.id(), 100));
    }

    private int countRows(String sql, TenantId tenantId) {
        Integer count = contexts.callWithTenantId(tenantId,
                () -> jdbc.queryForObject(sql, Integer.class, tenantId.value()));
        return count == null ? 0 : count;
    }

    private Customer customer(TenantContext context, String name) throws Exception {
        return inContext(context, () -> customers.create(name, null, List.of(new PhoneInput(
                "8888" + String.format("%04d", Math.floorMod(UUID.randomUUID().hashCode(), 10000)), "CR", true))));
    }

    private Customer consentedCustomer(TenantContext context, String name) throws Exception {
        Customer customer = customer(context, name);
        var policy = inContext(context, () -> contacts.get(customer.id()));
        UUID contactId = customer.phones().getFirst().id();
        inContext(context, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
        return customer;
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    /**
     * Waits until a backend blocks on a lock: the racing scan inserting the cycle the holder has not committed.
     * {@code pg_locks} is read rather than {@code pg_stat_activity}, which hides other roles' wait events.
     */
    private static void awaitLockWaiter() throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            while (System.nanoTime() < deadline) {
                if (TenantSecurityIntegrationFixture.queryInt(connection, """
                        SELECT count(*) FROM pg_locks WHERE NOT granted
                        """) > 0) {
                    return;
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError("The racing scan never waited for the uncommitted candidate");
    }
}
