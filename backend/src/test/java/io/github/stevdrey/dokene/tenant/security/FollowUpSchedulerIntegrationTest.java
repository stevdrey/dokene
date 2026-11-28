package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.migrationConnection;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryInt;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryUuids;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.testsupport.MutableClock;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateGeneration;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateGenerator;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateProperties;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateRepository;
import io.github.stevdrey.dokene.followup.application.FollowUpProviderHealth;
import io.github.stevdrey.dokene.followup.application.FollowUpRetryPolicy;
import io.github.stevdrey.dokene.followup.application.FollowUpSchedulerProperties;
import io.github.stevdrey.dokene.followup.application.FollowUpSchedulingCoordinator;
import io.github.stevdrey.dokene.followup.application.FollowUpSchedulingCoordinator.TickSummary;
import io.github.stevdrey.dokene.followup.application.FollowUpSchedulingTenants;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkAdmission;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkLedger;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkLedgerRepository;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkProcessor;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkProperties;
import io.github.stevdrey.dokene.followup.application.RecordOnlyFollowUpWorkHandler;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkClaim;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkItem;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkState;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.MaintenanceTask;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Issue 463 (ADR 0040): the recurring scheduler against PostgreSQL with forced RLS and the runtime role. Two
 * coordinators with their own worker ids stand for two backend instances sharing one database; a new coordinator with
 * a later clock stands for an instance restarted after another one stopped with a claim in hand.
 */
@SpringBootTest(properties = "dokene.ai.provider=fake")
class FollowUpSchedulerIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
    }

    @Autowired FollowUpSchedulingCoordinator configuredScheduler;
    @Autowired FollowUpSchedulingTenants schedulingTenants;
    @Autowired FollowUpWorkLedgerRepository workRepository;
    @Autowired FollowUpCandidateRepository candidates;
    @Autowired FollowUpRetryPolicy retries;
    @Autowired FollowUpService followUps;
    @Autowired ContactPolicyService contacts;
    @Autowired CustomerService customers;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired DatabaseContextSigner signer;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private Tenant tenantA;
    private Tenant tenantB;
    private TenantContext contextA;
    private TenantContext contextB;
    private Instant now;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        tenantA = seedTenant(tenants, contexts, "Scheduler A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, contexts, "Scheduler B " + UUID.randomUUID(), now);
        contextA = context(seedMembership(memberships, contexts, tenantA.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
        contextB = context(seedMembership(memberships, contexts, tenantB.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
    }

    @Test
    void theConfiguredSchedulerIsOffByDefault() throws Exception {
        dueCustomers(contextA, 2);

        assertThat(configuredScheduler.tick()).isEqualTo(new TickSummary(TickSummary.Outcome.DISABLED, 0, 0, 0, 0, 0,
                0, 0, 0, false));

        assertThat(contexts.callWithTenantId(tenantA.id(), () -> candidates.findCandidates(tenantA.id(), 10)))
                .isEmpty();
    }

    @Test
    void theTenantFinderListsOnlyActiveTenantsInKeysetPages() {
        Tenant suspended = seedTenant(tenants, contexts, "Scheduler suspended " + UUID.randomUUID(), now);
        suspended.suspend(now.plusSeconds(1));
        contexts.callWithTenantId(suspended.id(), () -> tenants.save(suspended));

        List<TenantId> all = allActiveTenants();

        assertThat(all).contains(tenantA.id(), tenantB.id()).doesNotContain(suspended.id());
        TenantId first = all.getFirst();
        assertThat(schedulingTenants.activeTenantsAfter(Optional.empty(), 1)).containsExactly(first);
        assertThat(schedulingTenants.activeTenantsAfter(Optional.of(first), 500)).isEqualTo(all.subList(1,
                all.size()));
        assertThat(schedulingTenants.activeTenantsAfter(Optional.of(all.getLast()), 500)).isEmpty();
    }

    @Test
    void theTenantFinderNeedsItsOwnMaintenanceCapability() throws Exception {
        String finder = "SELECT tenant_id FROM dokene.find_follow_up_scheduling_tenants(?::text, ?::text, NULL::uuid, "
                + "500)";
        SignedDatabaseContext capability = signer.issueMaintenanceContext(MaintenanceTask.FOLLOW_UP_SCHEDULING);
        SignedDatabaseContext otherTask = signer.issueMaintenanceContext(MaintenanceTask.DISPATCH_RECOVERY);
        SignedDatabaseContext tenantScope = signer.issueTenantContext(tenantA.id());

        try (Connection runtime = runtimeConnectionWithoutContext()) {
            assertThat(queryUuids(runtime, finder, capability.payload(), capability.signature()))
                    .contains(tenantA.id().value(), tenantB.id().value());
            assertThat(queryUuids(runtime, finder, null, null)).isEmpty();
            assertThat(queryUuids(runtime, finder, capability.payload(), "0".repeat(64))).isEmpty();
            assertThat(queryUuids(runtime, finder, otherTask.payload(), otherTask.signature())).isEmpty();
            assertThat(queryUuids(runtime, finder, tenantScope.payload(), tenantScope.signature())).isEmpty();
            // Without the finder, tenant RLS hides every tenant from a session with no tenant context.
            assertThat(queryInt(runtime, "SELECT count(*) FROM dokene.tenants")).isZero();
        }
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_proc p WHERE p.proname = ? AND p.prosecdef "
                    + "AND NOT has_function_privilege('public', p.oid, 'EXECUTE') "
                    + "AND has_function_privilege('dokene_runtime', p.oid, 'EXECUTE')",
                    "find_follow_up_scheduling_tenants")).isEqualTo(1);
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_proc WHERE proname = ? AND proargnames = "
                    + "ARRAY['capability_payload', 'capability_signature', 'after_tenant_id', 'max_rows', "
                    + "'tenant_id']", "find_follow_up_scheduling_tenants")).isEqualTo(1);
        }
    }

    @Test
    void twoInstancesTickingAtOnceRecordAndCompleteEachCycleOnceWithoutSending() throws Exception {
        dueCustomers(contextA, 5);
        dueCustomers(contextB, 3);
        FollowUpSchedulingCoordinator instanceA = coordinator("instance-a", Clock.fixed(now, ZoneOffset.UTC));
        FollowUpSchedulingCoordinator instanceB = coordinator("instance-b", Clock.fixed(now, ZoneOffset.UTC));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = executor.submit(() -> tickRepeatedly(start, instanceA));
            Future<Integer> b = executor.submit(() -> tickRepeatedly(start, instanceB));
            start.countDown();
            a.get(60, TimeUnit.SECONDS);
            b.get(60, TimeUnit.SECONDS);
        }

        for (Tenant tenant : List.of(tenantA, tenantB)) {
            int customersDue = tenant == tenantA ? 5 : 3;
            assertThat(contexts.callWithTenantId(tenant.id(), () -> candidates.findCandidates(tenant.id(), 100)))
                    .hasSize(customersDue);
            List<FollowUpWorkItem> items = items(tenant);
            assertThat(items).hasSize(customersDue).allSatisfy(item -> {
                assertThat(item.state()).isEqualTo(FollowUpWorkState.COMPLETED);
                assertThat(item.attempts()).isEqualTo(1);
            });
            assertThat(items).extracting(item -> item.claimedBy().orElseThrow().value())
                    .isSubsetOf("instance-a", "instance-b");
            // A record, not an action: no message was created for any customer.
            assertThat(contexts.callWithTenantId(tenant.id(),
                    () -> jdbc.queryForObject("SELECT count(*) FROM dokene.outbound_messages", Integer.class)))
                    .isZero();
        }
    }

    @Test
    void aRestartedInstanceTakesOverTheClaimAStoppedInstanceLeftBehind() throws Exception {
        dueCustomers(contextA, 1);
        generator(Clock.fixed(now, ZoneOffset.UTC)).generate(tenantA.id());
        FollowUpWorkLedger stoppedInstance = ledger(Clock.fixed(now, ZoneOffset.UTC));
        stoppedInstance.enqueue(tenantA.id());
        List<FollowUpWorkClaim> leftBehind = stoppedInstance.claim(tenantA.id(), new FollowUpWorkerId("stopped"), 1);
        assertThat(leftBehind).hasSize(1);

        MutableClock restartedClock = new MutableClock(now.plusSeconds(1));
        FollowUpSchedulingCoordinator restarted = coordinator("restarted", restartedClock);
        restarted.start();
        // While the lease holds, the restarted instance leaves the item to its holder.
        assertThat(restarted.tick().itemsClaimed()).isZero();
        restartedClock.advance(LEASE);
        TickSummary takeover = restarted.tick();

        assertThat(takeover.itemsClaimed()).isEqualTo(1);
        assertThat(takeover.itemsCompleted()).isEqualTo(1);
        FollowUpWorkItem item = items(tenantA).getFirst();
        assertThat(item.state()).isEqualTo(FollowUpWorkState.COMPLETED);
        assertThat(item.attempts()).isEqualTo(2);
        assertThat(item.claimedBy()).contains(new FollowUpWorkerId("restarted"));
        // The stopped instance's claim is stale and can no longer complete the item.
        assertThat(stoppedInstance.renew(leftBehind.getFirst())).isEmpty();
        restarted.stop();
        assertThat(restarted.tick().outcome()).isEqualTo(TickSummary.Outcome.STOPPED);
    }

    @Test
    void theProcessingKillSwitchRecordsCandidatesButNeverEnqueuesThem() throws Exception {
        dueCustomers(contextA, 2);
        FollowUpSchedulingCoordinator generationOnly = coordinator(new FollowUpSchedulerProperties(true, true, false,
                null, 500, 5, null, null, "generation-only"), Clock.fixed(now, ZoneOffset.UTC), null);

        assertThat(generationOnly.tick().candidatesRecorded()).isGreaterThanOrEqualTo(2);

        assertThat(contexts.callWithTenantId(tenantA.id(), () -> candidates.findCandidates(tenantA.id(), 10)))
                .hasSize(2);
        assertThat(items(tenantA)).isEmpty();
    }

    @Test
    void aTenantWithoutBudgetHasItsWorkEnqueuedButNotClaimed() throws Exception {
        dueCustomers(contextA, 1);
        dueCustomers(contextB, 1);
        FollowUpWorkAdmission onlyB = (tenant, requested) -> tenant.equals(tenantB.id()) ? requested : 0;
        FollowUpSchedulingCoordinator coordinator = coordinator(new FollowUpSchedulerProperties(true, true, true, null,
                500, 5, null, null, "budgeted"), Clock.fixed(now, ZoneOffset.UTC), onlyB);

        coordinator.tick();

        assertThat(items(tenantA)).singleElement()
                .satisfies(item -> assertThat(item.state()).isEqualTo(FollowUpWorkState.PENDING));
        assertThat(items(tenantB)).singleElement()
                .satisfies(item -> assertThat(item.state()).isEqualTo(FollowUpWorkState.COMPLETED));
    }

    @Test
    void theRotationWalksEveryActiveTenantThroughTheRealFinderAndWraps() {
        List<TenantId> all = allActiveTenants();
        assertThat(all).hasSizeLessThan(500).contains(tenantA.id(), tenantB.id());
        List<Optional<TenantId>> cursors = new ArrayList<>();
        List<TenantId> listed = new ArrayList<>();
        FollowUpSchedulingTenants recording = (after, limit) -> {
            cursors.add(after);
            List<TenantId> page = schedulingTenants.activeTenantsAfter(after, limit);
            listed.addAll(page);
            return page;
        };
        // Generation through a scan that records nothing and processing off: each tick still rotates through the real
        // V51 finder, cursor and limit included, but touches no tenant, so the shared database is left alone.
        FollowUpCandidateGenerator scanOnly = mock(FollowUpCandidateGenerator.class);
        when(scanOnly.generate(any())).thenAnswer(invocation -> invocation.getArgument(0) == null ? null
                : new FollowUpCandidateGeneration(invocation.getArgument(0), now,
                        FollowUpCandidateGeneration.Outcome.SCANNED, List.of(), false));
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        FollowUpWorkLedger ledger = ledger(clock);
        FollowUpSchedulingCoordinator coordinator = new FollowUpSchedulingCoordinator(recording, scanOnly,
                ledger, new FollowUpWorkProcessor(ledger, retries,
                        new StaticListableBeanFactory().getBeanProvider(FollowUpProviderHealth.class), clock),
                new RecordOnlyFollowUpWorkHandler(),
                new StaticListableBeanFactory().getBeanProvider(FollowUpWorkAdmission.class), contexts,
                new FollowUpSchedulerProperties(true, true, false, null, 2, 5, null, null, "rotation"),
                workProperties(), clock);

        int visited = 0;
        for (int tick = 0; tick < all.size() + 2 && listed.size() <= all.size(); tick++) {
            TickSummary summary = coordinator.tick();
            assertThat(summary.outcome()).isEqualTo(TickSummary.Outcome.RAN);
            assertThat(summary.tenantsVisited()).isLessThanOrEqualTo(2);
            visited += summary.tenantsVisited();
        }

        // Every active tenant once, in id order, then the rotation starts again from the first.
        assertThat(listed.subList(0, all.size())).isEqualTo(all);
        assertThat(listed.get(all.size())).isEqualTo(all.getFirst());
        assertThat(visited).isEqualTo(listed.size());
        assertThat(cursors.getFirst()).isEmpty();
        assertThat(cursors).filteredOn(Optional::isEmpty).hasSize(2);
        assertThat(contexts.callWithTenantId(tenantA.id(), () -> candidates.findCandidates(tenantA.id(), 10)))
                .isEmpty();
    }

    private int tickRepeatedly(CountDownLatch start, FollowUpSchedulingCoordinator coordinator) throws Exception {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        int claimed = 0;
        // Without a provider health adapter each tenant gets one probe claim per tick (ADR 0039), so ten ticks per
        // instance drain five items per tenant even when the other instance takes none.
        for (int tick = 0; tick < 10; tick++) {
            TickSummary summary = coordinator.tick();
            assertThat(summary.outcome()).isEqualTo(TickSummary.Outcome.RAN);
            assertThat(summary.failures()).isZero();
            claimed += summary.itemsClaimed();
        }
        return claimed;
    }

    /** Only this test's tenants, so the shared database's other tenants are never touched. */
    private FollowUpSchedulingTenants testTenants() {
        Set<TenantId> mine = Set.of(tenantA.id(), tenantB.id());
        return (after, limit) -> schedulingTenants.activeTenantsAfter(Optional.empty(), 500).stream()
                .filter(mine::contains).toList();
    }

    private List<TenantId> allActiveTenants() {
        return schedulingTenants.activeTenantsAfter(Optional.empty(), 500);
    }

    private FollowUpSchedulingCoordinator coordinator(String worker, Clock clock) {
        return coordinator(new FollowUpSchedulerProperties(true, true, true, null, 500, 5, null, null, worker), clock,
                null);
    }

    private FollowUpSchedulingCoordinator coordinator(FollowUpSchedulerProperties properties, Clock clock,
            FollowUpWorkAdmission admission) {
        FollowUpWorkLedger ledger = ledger(clock);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (admission != null) {
            beans.addBean("admission", admission);
        }
        FollowUpWorkProcessor processor = new FollowUpWorkProcessor(ledger, retries,
                new StaticListableBeanFactory().getBeanProvider(FollowUpProviderHealth.class), clock);
        return new FollowUpSchedulingCoordinator(testTenants(), generator(clock), ledger, processor,
                new RecordOnlyFollowUpWorkHandler(), beans.getBeanProvider(FollowUpWorkAdmission.class), contexts,
                properties, workProperties(), clock);
    }

    private FollowUpCandidateGenerator generator(Clock clock) {
        return new FollowUpCandidateGenerator(candidates, tenants, contexts, new FollowUpCandidateProperties(100),
                transactionManager, clock);
    }

    private FollowUpWorkLedger ledger(Clock clock) {
        return new FollowUpWorkLedger(workRepository, contexts, workProperties(), transactionManager, clock);
    }

    private static FollowUpWorkProperties workProperties() {
        return new FollowUpWorkProperties(20, LEASE, 3);
    }

    private void dueCustomers(TenantContext context, int count) throws Exception {
        for (int index = 0; index < count; index++) {
            Customer customer = consentedCustomer(context, "Scheduler customer " + index);
            inContext(context, () -> followUps.configureCustomer(customer.id(), 7, today, 0));
        }
    }

    private List<FollowUpWorkItem> items(Tenant tenant) {
        return contexts.callWithTenantId(tenant.id(), () -> workRepository.findItems(tenant.id(), 100));
    }

    private Customer consentedCustomer(TenantContext context, String name) throws Exception {
        Customer customer = inContext(context, () -> customers.create(name, null, List.of(new PhoneInput(
                "8888" + String.format("%04d", Math.floorMod(UUID.randomUUID().hashCode(), 10000)), "CR", true))));
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
}
