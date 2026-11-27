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
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateGenerator;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateProperties;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateRepository;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkLedger;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkLedgerRepository;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkProperties;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkTransition;
import io.github.stevdrey.dokene.followup.domain.FollowUpCandidate;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkClaim;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkFailure;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkItem;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkState;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.Connection;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue 458 (ADR 0037): the durable work ledger against PostgreSQL with forced RLS and the runtime role. Two ledgers
 * with their own worker ids and clocks stand for two backend instances sharing one database.
 */
@SpringBootTest(properties = "dokene.ai.provider=fake")
class FollowUpWorkLedgerIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final FollowUpWorkerId INSTANCE_A = new FollowUpWorkerId("instance-a");
    private static final FollowUpWorkerId INSTANCE_B = new FollowUpWorkerId("instance-b");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
    }

    @Autowired FollowUpWorkLedgerRepository repository;
    @Autowired FollowUpCandidateRepository candidates;
    @Autowired FollowUpService followUps;
    @Autowired ContactPolicyService contacts;
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
    private Instant now;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        tenantA = seedTenant(tenants, contexts, "Work A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, contexts, "Work B " + UUID.randomUUID(), now);
        contextA = context(seedMembership(memberships, contexts, tenantA.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
        contextB = context(seedMembership(memberships, contexts, tenantB.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
    }

    @Test
    void twoInstancesClaimingAtOnceNeverClaimTheSameItem() throws Exception {
        dueCustomers(contextA, tenantA, 8);
        assertThat(ledger(now, 100, 3).enqueue(tenantA.id())).isEqualTo(8);
        FollowUpWorkLedger instanceA = ledger(now, 3, 3);
        FollowUpWorkLedger instanceB = ledger(now, 3, 3);
        CountDownLatch start = new CountDownLatch(1);

        List<FollowUpWorkClaim> claimedByA;
        List<FollowUpWorkClaim> claimedByB;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<FollowUpWorkClaim>> a = executor.submit(() -> drain(start, instanceA, INSTANCE_A));
            Future<List<FollowUpWorkClaim>> b = executor.submit(() -> drain(start, instanceB, INSTANCE_B));
            start.countDown();
            claimedByA = a.get(30, TimeUnit.SECONDS);
            claimedByB = b.get(30, TimeUnit.SECONDS);
        }

        Set<UUID> claimed = new HashSet<>();
        for (FollowUpWorkClaim claim : concat(claimedByA, claimedByB)) {
            assertThat(claimed.add(claim.candidateId())).as("claimed once: %s", claim.candidateId()).isTrue();
            assertThat(claim.attempt()).isEqualTo(1);
            assertThat(claim.leaseExpiresAt()).isEqualTo(now.plus(LEASE));
        }
        assertThat(claimed).hasSize(8);
        assertThat(claimedByA).allSatisfy(claim -> assertThat(claim.worker()).isEqualTo(INSTANCE_A));
        assertThat(claimedByB).allSatisfy(claim -> assertThat(claim.worker()).isEqualTo(INSTANCE_B));
        assertThat(items(tenantA)).extracting(FollowUpWorkItem::state).containsOnly(FollowUpWorkState.CLAIMED);
    }

    @Test
    void aClaimSkipsRowsAnotherInstanceHoldsWithoutWaiting() throws Exception {
        dueCustomers(contextA, tenantA, 4);
        ledger(now, 100, 3).enqueue(tenantA.id());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<FollowUpWorkClaim>> holder = executor.submit(() -> contexts.callWithTenantId(tenantA.id(),
                    () -> transaction.execute(status -> {
                        List<FollowUpWorkClaim> claims = repository.claim(tenantA.id(), INSTANCE_A, now,
                                now.plus(LEASE), 3, 2);
                        held.countDown();
                        await(release);
                        return claims;
                    })));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            // The holder has not committed; the other instance takes the remaining rows at once.
            List<FollowUpWorkClaim> other = executor.submit(() -> ledger(now, 3, 3).claim(tenantA.id(), INSTANCE_B, 3))
                    .get(10, TimeUnit.SECONDS);
            release.countDown();
            List<FollowUpWorkClaim> holderClaims = holder.get(30, TimeUnit.SECONDS);

            assertThat(other).hasSize(2);
            assertThat(holderClaims).hasSize(2);
            assertThat(other).extracting(FollowUpWorkClaim::candidateId)
                    .doesNotContainAnyElementsOf(holderClaims.stream().map(FollowUpWorkClaim::candidateId).toList());
        }
    }

    @Test
    void aCrashedInstancesItemIsClaimedAgainAfterItsLeaseAndTheStaleOwnerCannotCommit() throws Exception {
        dueCustomers(contextA, tenantA, 1);
        ledger(now, 100, 3).enqueue(tenantA.id());
        FollowUpWorkClaim crashed = ledger(now, 5, 3).claim(tenantA.id(), INSTANCE_A, 5).getFirst();

        // Before the lease expires nobody else may take the item.
        assertThat(ledger(now.plus(LEASE).minusMillis(1), 5, 3).claim(tenantA.id(), INSTANCE_B, 5)).isEmpty();

        Instant afterExpiry = now.plus(LEASE);
        FollowUpWorkLedger instanceB = ledger(afterExpiry, 5, 3);
        FollowUpWorkClaim takeover = instanceB.claim(tenantA.id(), INSTANCE_B, 5).getFirst();
        assertThat(takeover.candidateId()).isEqualTo(crashed.candidateId());
        assertThat(takeover.attempt()).isEqualTo(2);
        assertThat(takeover.claimToken()).isNotEqualTo(crashed.claimToken());

        // The crashed instance comes back: every fenced write of its old claim is rejected.
        FollowUpWorkLedger revived = ledger(afterExpiry.plusSeconds(1), 5, 3);
        assertThat(revived.renew(crashed)).isEmpty();
        assertThat(revived.complete(crashed)).isEqualTo(FollowUpWorkTransition.STALE);
        assertThat(revived.fail(crashed, FollowUpWorkFailure.PROCESSING_ERROR, afterExpiry.plusSeconds(60)))
                .isEqualTo(FollowUpWorkTransition.STALE);
        assertThat(revived.requireRecovery(crashed, FollowUpWorkFailure.OUTCOME_UNKNOWN))
                .isEqualTo(FollowUpWorkTransition.STALE);
        // A claim with the current attempt number but a forged token is just as stale.
        FollowUpWorkClaim forged = new FollowUpWorkClaim(takeover.candidateId(), takeover.tenantId(),
                takeover.customerId(), takeover.dueDate(), INSTANCE_A, takeover.attempt(), UUID.randomUUID(),
                takeover.claimedAt(), takeover.leaseExpiresAt());
        assertThat(revived.complete(forged)).isEqualTo(FollowUpWorkTransition.STALE);
        assertThat(items(tenantA)).singleElement().satisfies(item -> {
            assertThat(item.state()).isEqualTo(FollowUpWorkState.CLAIMED);
            assertThat(item.attempts()).isEqualTo(2);
            assertThat(item.claimedBy()).contains(INSTANCE_B);
        });

        assertThat(instanceB.complete(takeover)).isEqualTo(FollowUpWorkTransition.COMPLETED);
        assertThat(items(tenantA)).singleElement().satisfies(item -> {
            assertThat(item.state()).isEqualTo(FollowUpWorkState.COMPLETED);
            assertThat(item.finishedAt()).contains(afterExpiry);
        });
        assertThat(ledger(afterExpiry.plus(LEASE.multipliedBy(4)), 5, 3).claim(tenantA.id(), INSTANCE_A, 5))
                .isEmpty();
    }

    @Test
    void anExpiredClaimThatNobodyTookIsStillStale() throws Exception {
        dueCustomers(contextA, tenantA, 1);
        ledger(now, 100, 3).enqueue(tenantA.id());
        FollowUpWorkClaim claim = ledger(now, 5, 3).claim(tenantA.id(), INSTANCE_A, 5).getFirst();

        assertThat(ledger(now.plus(LEASE), 5, 3).complete(claim)).isEqualTo(FollowUpWorkTransition.STALE);
        assertThat(ledger(now.plus(LEASE), 5, 3).renew(claim)).isEmpty();
        assertThat(items(tenantA)).singleElement()
                .satisfies(item -> assertThat(item.state()).isEqualTo(FollowUpWorkState.CLAIMED));
    }

    @Test
    void renewingKeepsTheItemFromOtherInstancesUntilTheNewExpiry() throws Exception {
        dueCustomers(contextA, tenantA, 1);
        ledger(now, 100, 3).enqueue(tenantA.id());
        FollowUpWorkClaim claim = ledger(now, 5, 3).claim(tenantA.id(), INSTANCE_A, 5).getFirst();

        Instant renewedAt = now.plusSeconds(20);
        FollowUpWorkClaim renewed = ledger(renewedAt, 5, 3).renew(claim).orElseThrow();
        assertThat(renewed.leaseExpiresAt()).isEqualTo(renewedAt.plus(LEASE));
        // A renewal never shortens the lease, even from a clock behind the first renewal.
        assertThat(ledger(now.plusSeconds(1), 5, 3).renew(renewed)).hasValueSatisfying(
                again -> assertThat(again.leaseExpiresAt()).isEqualTo(renewedAt.plus(LEASE)));

        assertThat(ledger(now.plus(LEASE).plusSeconds(5), 5, 3).claim(tenantA.id(), INSTANCE_B, 5)).isEmpty();
        assertThat(ledger(now.plus(LEASE).plusSeconds(10), 5, 3).complete(renewed))
                .isEqualTo(FollowUpWorkTransition.COMPLETED);
    }

    @Test
    void failedAttemptsRetryWhenDueAndExhaustAtTheLimit() throws Exception {
        dueCustomers(contextA, tenantA, 2);
        ledger(now, 100, 2).enqueue(tenantA.id());
        List<FollowUpWorkClaim> first = ledger(now, 5, 2).claim(tenantA.id(), INSTANCE_A, 5);
        assertThat(first).hasSize(2);
        FollowUpWorkClaim failing = first.get(0);
        FollowUpWorkClaim abandoned = first.get(1);

        Instant retryAt = now.plusSeconds(60);
        assertThat(ledger(now.plusSeconds(1), 5, 2).fail(failing, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE,
                retryAt)).isEqualTo(FollowUpWorkTransition.RETRY_SCHEDULED);
        assertThat(item(tenantA, failing.candidateId())).satisfies(item -> {
            assertThat(item.state()).isEqualTo(FollowUpWorkState.RETRY_DUE);
            assertThat(item.availableAt()).isEqualTo(retryAt);
            assertThat(item.lastFailure()).contains(FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE);
        });
        // A retry is not claimable before it is due; the abandoned claim is not either before its lease expires.
        assertThat(ledger(now.plusSeconds(29), 5, 2).claim(tenantA.id(), INSTANCE_B, 5)).isEmpty();

        // At the retry instant the retry is claimed again, and so is the abandoned claim, whose lease has expired:
        // both for their second and last attempt.
        List<FollowUpWorkClaim> second = ledger(retryAt, 5, 2).claim(tenantA.id(), INSTANCE_B, 5);
        assertThat(second).extracting(FollowUpWorkClaim::candidateId)
                .containsExactlyInAnyOrder(failing.candidateId(), abandoned.candidateId());
        assertThat(second).extracting(FollowUpWorkClaim::attempt).containsOnly(2);
        FollowUpWorkClaim lastTry = second.stream()
                .filter(claim -> claim.candidateId().equals(failing.candidateId())).findFirst().orElseThrow();
        assertThat(ledger(retryAt.plusSeconds(1), 5, 2).fail(lastTry, FollowUpWorkFailure.PROCESSING_ERROR,
                retryAt.plusSeconds(60))).isEqualTo(FollowUpWorkTransition.EXHAUSTED);

        // The abandoned item's second lease expires too: it is exhausted as LEASE_EXPIRED, never claimed a third time.
        Instant late = retryAt.plus(LEASE).plusSeconds(120);
        assertThat(ledger(late, 5, 2).claim(tenantA.id(), INSTANCE_A, 5)).isEmpty();
        assertThat(items(tenantA)).allSatisfy(item -> {
            assertThat(item.state()).isEqualTo(FollowUpWorkState.EXHAUSTED);
            assertThat(item.attempts()).isEqualTo(2);
            assertThat(item.finishedAt()).isPresent();
        });
        assertThat(item(tenantA, abandoned.candidateId()).lastFailure()).contains(FollowUpWorkFailure.LEASE_EXPIRED);
        assertThat(item(tenantA, failing.candidateId()).lastFailure())
                .contains(FollowUpWorkFailure.PROCESSING_ERROR);
    }

    @Test
    void aCustomersOpenCandidatesAreHandledAsOneFollowUp() throws Exception {
        Customer customer = consentedCustomer(contextA, "Cadence change customer");
        var policy = inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today.minusDays(3), 0));
        generate(tenantA);
        inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today, policy.version()));
        generate(tenantA);
        List<FollowUpCandidate> cycles = contexts.callWithTenantId(tenantA.id(),
                () -> candidates.findCandidates(tenantA.id(), 10));
        assertThat(cycles).hasSize(2);

        // One open item per customer: its latest cycle. Enqueueing again adds nothing while it is open.
        FollowUpWorkLedger ledger = ledger(now, 100, 3);
        assertThat(ledger.enqueue(tenantA.id())).isEqualTo(1);
        assertThat(ledger.enqueue(tenantA.id())).isZero();
        assertThat(items(tenantA)).singleElement()
                .satisfies(item -> assertThat(item.dueDate()).isEqualTo(today));
        // The partial unique index refuses a second open item even when the application check is bypassed.
        assertThatThrownBy(() -> contexts.callWithTenantId(tenantA.id(), () -> jdbc.update("""
                INSERT INTO dokene.follow_up_work_items (candidate_id, tenant_id, customer_id, due_date, state,
                    attempts, available_at, enqueued_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', 0, now(), now(), now())
                """, cycles.getFirst().id(), tenantA.id().value(), customer.id().value(),
                Date.valueOf(cycles.getFirst().dueDate())))).isInstanceOf(RuntimeException.class);

        FollowUpWorkClaim claim = ledger(now.plusSeconds(1), 5, 3).claim(tenantA.id(), INSTANCE_A, 5).getFirst();
        assertThat(ledger(now.plusSeconds(2), 5, 3).complete(claim)).isEqualTo(FollowUpWorkTransition.COMPLETED);

        // The older cycle was resolved with it, so nothing is left to enqueue or claim.
        assertThat(items(tenantA)).hasSize(2).allSatisfy(
                item -> assertThat(item.state()).isEqualTo(FollowUpWorkState.COMPLETED));
        assertThat(item(tenantA, cycles.getFirst().id()).collapsedInto()).contains(claim.candidateId());
        assertThat(item(tenantA, cycles.getFirst().id()).attempts()).isZero();
        assertThat(ledger.enqueue(tenantA.id())).isZero();
    }

    @Test
    void candidatesBeyondTheCollapseBoundNeverReopenButANewerCycleDoes() throws Exception {
        Customer customer = consentedCustomer(contextA, "Many cycles customer");
        var first = inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today.minusDays(5), 0));
        generate(tenantA);
        var second = inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today.minusDays(3),
                first.version()));
        generate(tenantA);
        var third = inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today, second.version()));
        generate(tenantA);
        assertThat(ledger(now, 100, 3).enqueue(tenantA.id())).isEqualTo(1);
        FollowUpWorkClaim claim = ledger(now.plusSeconds(1), 5, 3).claim(tenantA.id(), INSTANCE_A, 5).getFirst();
        assertThat(claim.dueDate()).isEqualTo(today);

        // Complete with a collapse bound of one, so one older cycle is left without an item.
        Instant completedAt = now.plusSeconds(2);
        Integer collapsed = contexts.callWithTenantId(tenantA.id(), () -> new TransactionTemplate(transactionManager)
                .execute(status -> repository.complete(claim, completedAt)
                        ? repository.collapseInto(claim, completedAt, 1) : -1));
        assertThat(collapsed).isEqualTo(1);
        assertThat(items(tenantA)).hasSize(2);

        // The completed claim covers the leftover cycle: enqueue records it as collapsed and never reopens it.
        assertThat(ledger(now.plusSeconds(3), 100, 3).enqueue(tenantA.id())).isZero();
        assertThat(ledger(now.plusSeconds(3), 5, 3).claim(tenantA.id(), INSTANCE_B, 5)).isEmpty();
        assertThat(items(tenantA)).hasSize(3).allSatisfy(
                item -> assertThat(item.state()).isEqualTo(FollowUpWorkState.COMPLETED));
        assertThat(items(tenantA)).filteredOn(item -> item.collapsedInto().isPresent())
                .extracting(item -> item.collapsedInto().orElseThrow()).containsOnly(claim.candidateId());

        // A cycle generated after the claim is new work and is enqueued, even when the generating instance's clock is
        // a minute behind the claimant's: coverage compares database instants only.
        inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today.minusDays(1),
                third.version()));
        generateAt(tenantA, now.minusSeconds(60));
        assertThat(ledger(now.plusSeconds(11), 100, 3).enqueue(tenantA.id())).isEqualTo(1);
        assertThat(items(tenantA)).filteredOn(item -> item.state() == FollowUpWorkState.PENDING).singleElement()
                .satisfies(item -> assertThat(item.dueDate()).isEqualTo(today.minusDays(1)));
    }

    @Test
    void aSafelyExhaustedItemReleasesItsCustomerForANewerCycle() throws Exception {
        Customer customer = consentedCustomer(contextA, "Safely exhausted customer");
        var first = inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today.minusDays(3), 0));
        generate(tenantA);
        var second = inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today, first.version()));
        generate(tenantA);
        assertThat(ledger(now, 100, 1).enqueue(tenantA.id())).isEqualTo(1);
        FollowUpWorkClaim claim = ledger(now, 5, 1).claim(tenantA.id(), INSTANCE_A, 5).getFirst();
        assertThat(ledger(now.plusSeconds(1), 5, 1).fail(claim, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE,
                now.plusSeconds(60))).isEqualTo(FollowUpWorkTransition.EXHAUSTED);

        // The older cycle is covered by the exhausted attempt: it is recorded, not retried as another follow-up.
        assertThat(ledger(now.plusSeconds(2), 100, 1).enqueue(tenantA.id())).isZero();
        assertThat(items(tenantA)).hasSize(2).filteredOn(item -> item.collapsedInto().isPresent()).singleElement()
                .satisfies(item -> assertThat(item.collapsedInto()).contains(claim.candidateId()));

        // No external effect can have happened, so a newer cycle is not held back.
        inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today.minusDays(1),
                second.version()));
        generate(tenantA);
        assertThat(ledger(now.plusSeconds(3), 100, 1).enqueue(tenantA.id())).isEqualTo(1);
        assertThat(items(tenantA)).filteredOn(item -> item.state() == FollowUpWorkState.PENDING).singleElement()
                .satisfies(item -> assertThat(item.dueDate()).isEqualTo(today.minusDays(1)));
    }

    @Test
    void anItemThatNeedsRecoveryHoldsBackNewerWorkForItsCustomer() throws Exception {
        Customer customer = consentedCustomer(contextA, "Recovery customer");
        var policy = inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today.minusDays(3), 0));
        generate(tenantA);
        ledger(now, 100, 3).enqueue(tenantA.id());
        FollowUpWorkClaim claim = ledger(now, 5, 3).claim(tenantA.id(), INSTANCE_A, 5).getFirst();
        assertThat(ledger(now.plusSeconds(1), 5, 3).requireRecovery(claim, FollowUpWorkFailure.OUTCOME_UNKNOWN))
                .isEqualTo(FollowUpWorkTransition.EXHAUSTED);

        inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today, policy.version()));
        generate(tenantA);
        assertThat(ledger(now.plusSeconds(2), 100, 3).enqueue(tenantA.id())).isZero();
        assertThat(ledger(now.plus(LEASE).plusSeconds(5), 5, 3).claim(tenantA.id(), INSTANCE_B, 5)).isEmpty();
        assertThat(items(tenantA)).singleElement().satisfies(item -> {
            assertThat(item.state()).isEqualTo(FollowUpWorkState.EXHAUSTED);
            assertThat(item.lastFailure()).contains(FollowUpWorkFailure.OUTCOME_UNKNOWN);
        });
    }

    @Test
    void enqueueAndClaimAreBoundedAndContinueInDueOrder() throws Exception {
        List<Customer> due = new ArrayList<>();
        for (int days = 5; days >= 1; days--) {
            Customer customer = consentedCustomer(contextA, "Ordered customer " + days);
            int offset = days;
            inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, today.minusDays(offset), 0));
            due.add(customer);
        }
        generate(tenantA);
        FollowUpWorkLedger small = ledger(now, 2, 3);
        assertThat(small.enqueue(tenantA.id())).isEqualTo(2);
        assertThat(small.enqueue(tenantA.id())).isEqualTo(2);
        assertThat(small.enqueue(tenantA.id())).isEqualTo(1);
        assertThat(small.enqueue(tenantA.id())).isZero();

        List<FollowUpWorkClaim> firstBatch = small.claim(tenantA.id(), INSTANCE_A, 2);
        assertThat(firstBatch).extracting(FollowUpWorkClaim::customerId)
                .containsExactly(due.get(0).id(), due.get(1).id());
        assertThat(small.claim(tenantA.id(), INSTANCE_A, 2)).extracting(FollowUpWorkClaim::customerId)
                .containsExactly(due.get(2).id(), due.get(3).id());
        assertThat(small.claim(tenantA.id(), INSTANCE_A, 2)).hasSize(1);
        assertThatThrownBy(() -> small.claim(tenantA.id(), INSTANCE_A, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ledgerWorkStaysInsideItsTenant() throws Exception {
        dueCustomers(contextA, tenantA, 1);
        dueCustomers(contextB, tenantB, 1);
        FollowUpWorkLedger ledger = ledger(now, 100, 3);
        assertThat(ledger.enqueue(tenantA.id())).isEqualTo(1);
        assertThat(items(tenantB)).isEmpty();

        FollowUpWorkClaim claimA = ledger.claim(tenantA.id(), INSTANCE_A, 5).getFirst();
        assertThat(claimA.tenantId()).isEqualTo(tenantA.id());
        assertThat(ledger.claim(tenantB.id(), INSTANCE_B, 5)).isEmpty();
        assertThat(ledger.enqueue(tenantB.id())).isEqualTo(1);
        assertThat(ledger.claim(tenantB.id(), INSTANCE_B, 5)).singleElement()
                .satisfies(claim -> assertThat(claim.tenantId()).isEqualTo(tenantB.id()));

        // A claim relabelled with tenant B runs under tenant B's scope and cannot reach tenant A's item.
        FollowUpWorkClaim relabelled = new FollowUpWorkClaim(claimA.candidateId(), tenantB.id(), claimA.customerId(),
                claimA.dueDate(), claimA.worker(), claimA.attempt(), claimA.claimToken(), claimA.claimedAt(),
                claimA.leaseExpiresAt());
        assertThat(ledger.complete(relabelled)).isEqualTo(FollowUpWorkTransition.STALE);
        assertThat(ledger.renew(relabelled)).isEmpty();
        // Under tenant B's scope, RLS hides tenant A's items even when tenant A's id is asked for.
        assertThat(contexts.callWithTenantId(tenantB.id(), () -> repository.findItems(tenantA.id(), 10))).isEmpty();
        assertThat(contexts.callWithTenantId(tenantB.id(), () -> jdbc.update(
                "UPDATE dokene.follow_up_work_items SET state = 'COMPLETED', finished_at = now() WHERE tenant_id = ?",
                tenantA.id().value()))).isZero();
        assertThat(item(tenantA, claimA.candidateId()).state()).isEqualTo(FollowUpWorkState.CLAIMED);

        // The runtime role can never remove an item, and cannot move one to another tenant.
        assertThatThrownBy(() -> contexts.callWithTenantId(tenantA.id(), () -> jdbc.update(
                "DELETE FROM dokene.follow_up_work_items WHERE tenant_id = ?", tenantA.id().value())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> contexts.callWithTenantId(tenantA.id(), () -> jdbc.update(
                "UPDATE dokene.follow_up_work_items SET tenant_id = ? WHERE tenant_id = ?", tenantB.id().value(),
                tenantA.id().value()))).isInstanceOf(RuntimeException.class);
        assertThat(ledger.complete(claimA)).isEqualTo(FollowUpWorkTransition.COMPLETED);
    }

    @Test
    void anInactiveTenantGetsNoNewWorkOrClaims() throws Exception {
        dueCustomers(contextA, tenantA, 2);
        FollowUpWorkLedger ledger = ledger(now, 1, 3);
        assertThat(ledger.enqueue(tenantA.id())).isEqualTo(1);
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            TenantSecurityIntegrationFixture.executeUpdate(connection,
                    "UPDATE dokene.tenants SET status = 'SUSPENDED' WHERE id = ?", tenantA.id().value());
        }

        assertThat(ledger.enqueue(tenantA.id())).isZero();
        assertThat(ledger.claim(tenantA.id(), INSTANCE_A, 1)).isEmpty();
        assertThat(items(tenantA)).singleElement()
                .satisfies(item -> assertThat(item.state()).isEqualTo(FollowUpWorkState.PENDING));
    }

    private List<FollowUpWorkClaim> drain(CountDownLatch start, FollowUpWorkLedger ledger, FollowUpWorkerId worker)
            throws InterruptedException {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        List<FollowUpWorkClaim> claimed = new ArrayList<>();
        while (true) {
            List<FollowUpWorkClaim> batch = ledger.claim(tenantA.id(), worker, 3);
            if (batch.isEmpty()) {
                return claimed;
            }
            assertThat(batch).hasSizeLessThanOrEqualTo(3);
            claimed.addAll(batch);
        }
    }

    private static List<FollowUpWorkClaim> concat(List<FollowUpWorkClaim> left, List<FollowUpWorkClaim> right) {
        List<FollowUpWorkClaim> all = new ArrayList<>(left);
        all.addAll(right);
        return all;
    }

    private FollowUpWorkLedger ledger(Instant instant, int batchSize, int maxAttempts) {
        return new FollowUpWorkLedger(repository, contexts, new FollowUpWorkProperties(batchSize, LEASE, maxAttempts),
                transactionManager, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private void generate(Tenant tenant) {
        generateAt(tenant, now);
    }

    private void generateAt(Tenant tenant, Instant instant) {
        new FollowUpCandidateGenerator(candidates, tenants, contexts, new FollowUpCandidateProperties(100),
                transactionManager, Clock.fixed(instant, ZoneOffset.UTC)).generate(tenant.id());
    }

    private void dueCustomers(TenantContext context, Tenant tenant, int count) throws Exception {
        for (int index = 0; index < count; index++) {
            Customer customer = consentedCustomer(context, "Work customer " + index);
            inContext(context, () -> followUps.configureCustomer(customer.id(), 7, today, 0));
        }
        generate(tenant);
    }

    private List<FollowUpWorkItem> items(Tenant tenant) {
        return contexts.callWithTenantId(tenant.id(), () -> repository.findItems(tenant.id(), 100));
    }

    private FollowUpWorkItem item(Tenant tenant, UUID candidateId) {
        return items(tenant).stream().filter(item -> item.candidateId().equals(candidateId)).findFirst()
                .orElseThrow();
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
}
