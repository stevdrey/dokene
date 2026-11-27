// ABOUTME: Issue 461 (ADR 0038): bounded retries and explicit recovery of follow-up work against PostgreSQL with forced
// ABOUTME: RLS: transient, permanent and unknown outcomes, crash, exhaustion, stale claims and audited idempotent recovery.
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
import io.github.stevdrey.dokene.followup.application.FollowUpProviderHealth;
import io.github.stevdrey.dokene.followup.application.FollowUpRetryPolicy;
import io.github.stevdrey.dokene.followup.application.FollowUpRetryProperties;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkHandler;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkLedger;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkLedgerRepository;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkProcessor;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkProperties;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkRecoveryService;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkTransition;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkRecoveryService.Refusal;
import io.github.stevdrey.dokene.followup.domain.FollowUpRecoverableWork;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkAttemptResult;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkClaim;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkFailure;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkItem;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkRecoveryAction;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkState;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "dokene.ai.provider=fake")
class FollowUpWorkRecoveryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration INITIAL_BACKOFF = Duration.ofMinutes(1);
    private static final FollowUpWorkerId INSTANCE_A = new FollowUpWorkerId("instance-a");
    private static final FollowUpWorkerId INSTANCE_B = new FollowUpWorkerId("instance-b");
    private static final FollowUpWorkHandler COMPLETES = lease -> new FollowUpWorkAttemptResult.Completed();
    private static final FollowUpWorkHandler UNAVAILABLE = lease ->
            new FollowUpWorkAttemptResult.Transient(FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired FollowUpWorkLedgerRepository repository;
    @Autowired FollowUpWorkRecoveryService recovery;
    @Autowired FollowUpCandidateRepository candidates;
    @Autowired FollowUpService followUps;
    @Autowired ContactPolicyService contacts;
    @Autowired CustomerService customers;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired PlatformTransactionManager transactionManager;

    private Tenant tenantA;
    private Tenant tenantB;
    private TenantContext ownerA;
    private TenantContext operatorA;
    private TenantContext viewerA;
    private TenantContext ownerB;
    private Instant now;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        tenantA = seedTenant(tenants, contexts, "Recovery A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, contexts, "Recovery B " + UUID.randomUUID(), now);
        ownerA = member(tenantA, TenantRole.OWNER);
        operatorA = member(tenantA, TenantRole.OPERATOR);
        viewerA = member(tenantA, TenantRole.VIEWER);
        ownerB = member(tenantB, TenantRole.OWNER);
    }

    @Test
    void transientFailuresBackOffThenExhaustAndARequeueGivesAFreshBudget() throws Exception {
        UUID item = oneItem();

        // Attempt 1 fails; the retry waits for the jittered backoff of attempt 1.
        assertThat(processor(now).process(tenantA.id(), INSTANCE_A, 5, UNAVAILABLE).retryScheduled()).isEqualTo(1);
        FollowUpWorkItem retrying = item(tenantA, item);
        assertThat(retrying.state()).isEqualTo(FollowUpWorkState.RETRY_DUE);
        assertThat(retrying.lastFailure()).contains(FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE);
        assertThat(retrying.availableAt()).isAfter(now.plus(INITIAL_BACKOFF.multipliedBy(3).dividedBy(4)))
                .isBeforeOrEqualTo(now.plus(INITIAL_BACKOFF));
        assertThat(processor(now.plusSeconds(10)).process(tenantA.id(), INSTANCE_A, 5, COMPLETES).claimed()).isZero();

        // Attempts 2 and 3 fail; the third exhausts the item, which releases its customer (a safe failure).
        assertThat(processor(now.plusSeconds(61)).process(tenantA.id(), INSTANCE_A, 5, UNAVAILABLE).retryScheduled())
                .isEqualTo(1);
        assertThat(processor(now.plusSeconds(200)).process(tenantA.id(), INSTANCE_A, 5, UNAVAILABLE).exhausted())
                .isEqualTo(1);
        assertThat(item(tenantA, item).state()).isEqualTo(FollowUpWorkState.EXHAUSTED);
        assertThat(processor(now.plusSeconds(400)).process(tenantA.id(), INSTANCE_A, 5, COMPLETES).claimed()).isZero();

        List<FollowUpRecoverableWork> awaiting = inContext(operatorA, () -> recovery.recoverable(10));
        assertThat(awaiting).singleElement().satisfies(view -> {
            assertThat(view.candidateId()).isEqualTo(item);
            assertThat(view.outcomeUnknown()).isFalse();
            assertThat(view.attempts()).isEqualTo(3);
            assertThat(view.recoveryCount()).isZero();
        });

        var requeued = inContext(operatorA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.REQUEUE, 0));
        assertThat(requeued.applied()).isTrue();
        assertThat(requeued.item().state()).isEqualTo(FollowUpWorkState.RETRY_DUE);
        assertThat(requeued.item().recoveryCount()).isEqualTo(1);
        assertThat(auditRows(tenantA, "FOLLOW_UP_WORK_REQUEUED", requeued.item().customerId().value())).isEqualTo(1);
        assertThat(inContext(operatorA, () -> recovery.recoverable(10))).isEmpty();

        // The requeued item gets the full attempt budget again while its attempt numbers keep growing.
        Instant later = now.plusSeconds(500);
        assertThat(processor(later).process(tenantA.id(), INSTANCE_A, 5, UNAVAILABLE).retryScheduled()).isEqualTo(1);
        AtomicInteger attempt = new AtomicInteger();
        // Attempt 4's backoff is capped at 5 minutes.
        assertThat(processor(later.plusSeconds(301)).process(tenantA.id(), INSTANCE_A, 5, lease -> {
            attempt.set(lease.claim().attempt());
            return new FollowUpWorkAttemptResult.Completed();
        }).completed()).isEqualTo(1);
        assertThat(attempt).hasValue(5);
        assertThat(item(tenantA, item).state()).isEqualTo(FollowUpWorkState.COMPLETED);
    }

    @Test
    void aPermanentFailureIsExhaustedAtOnceWithoutHoldingBackNewerWork() throws Exception {
        UUID item = oneItem();

        assertThat(processor(now).process(tenantA.id(), INSTANCE_A, 5,
                lease -> new FollowUpWorkAttemptResult.Permanent(FollowUpWorkFailure.REJECTED)).exhausted())
                .isEqualTo(1);

        assertThat(item(tenantA, item)).satisfies(work -> {
            assertThat(work.state()).isEqualTo(FollowUpWorkState.EXHAUSTED);
            assertThat(work.attempts()).isEqualTo(1);
            assertThat(work.lastFailure()).contains(FollowUpWorkFailure.REJECTED);
        });
        // Nothing is ever retried automatically.
        assertThat(processor(now.plus(Duration.ofDays(1))).process(tenantA.id(), INSTANCE_A, 5, COMPLETES).claimed())
                .isZero();
        assertThat(inContext(ownerA, () -> recovery.recoverable(10))).extracting(FollowUpRecoverableWork::candidateId)
                .containsExactly(item);
    }

    @Test
    void anUnknownOutcomeHoldsTheCustomerUntilResolvedAndIsNeverRequeued() throws Exception {
        Customer customer = dueCustomer("Unknown outcome customer", today.minusDays(1));
        generate(tenantA);
        ledger(now).enqueue(tenantA.id());

        var summary = processor(now).process(tenantA.id(), INSTANCE_A, 5, lease -> {
            throw new IllegalStateException("the provider timed out after the request was sent");
        });
        assertThat(summary.recoveryRequired()).isEqualTo(1);
        UUID item = onlyItem(tenantA).candidateId();
        assertThat(item(tenantA, item).lastFailure()).contains(FollowUpWorkFailure.OUTCOME_UNKNOWN);

        // A newer cycle of the same customer waits: nothing is enqueued or claimed for it.
        inContext(ownerA, () -> followUps.configureCustomer(customer.id(), 7, today,
                followUps.customerPolicy(customer.id()).version()));
        generate(tenantA);
        assertThat(ledger(now.plusSeconds(1)).enqueue(tenantA.id())).isZero();

        assertThatThrownBy(() -> inContext(ownerA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.REQUEUE, 0)))
                .isInstanceOfSatisfying(FollowUpWorkRecoveryService.RecoveryRefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(Refusal.OUTCOME_UNKNOWN));
        assertThat(auditRows(tenantA, "FOLLOW_UP_WORK_REQUEUED", customer.id().value())).isZero();

        var resolved = inContext(ownerA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.RESOLVE, 0));
        assertThat(resolved.item().state()).isEqualTo(FollowUpWorkState.COMPLETED);
        assertThat(resolved.item().lastFailure()).isEqualTo(FollowUpWorkFailure.OUTCOME_UNKNOWN);
        assertThat(auditRows(tenantA, "FOLLOW_UP_WORK_RESOLVED", customer.id().value())).isEqualTo(1);

        // Resolving released the customer: the newer cycle is enqueued as its own follow-up.
        assertThat(ledger(now.plusSeconds(2)).enqueue(tenantA.id())).isEqualTo(1);
        assertThat(ledger(now.plusSeconds(2)).claim(tenantA.id(), INSTANCE_B, 5)).singleElement()
                .satisfies(next -> assertThat(next.dueDate()).isEqualTo(today));
    }

    @Test
    void aCrashedClaimIsTakenOverAndACrashOnTheLastAttemptNeedsRecovery() throws Exception {
        UUID item = oneItem();
        FollowUpWorkLedger instanceA = ledger(now);

        // Instance A claims and crashes; instance B takes the item over after the lease, and A's late outcome is stale.
        FollowUpWorkClaim crashed = instanceA.claim(tenantA.id(), INSTANCE_A, 5).getFirst();
        Instant afterLease = now.plus(LEASE);
        assertThat(processor(afterLease).process(tenantA.id(), INSTANCE_B, 5, UNAVAILABLE).retryScheduled())
                .isEqualTo(1);
        assertThat(ledger(afterLease.plusSeconds(1)).complete(crashed)).isEqualTo(FollowUpWorkTransition.STALE);

        // The last attempt's holder crashes too: the outcome is unknown, so the item waits for recovery.
        // Attempt 2's backoff is at most 2 minutes.
        Instant third = afterLease.plus(Duration.ofMinutes(2)).plusSeconds(1);
        ledger(third).claim(tenantA.id(), INSTANCE_A, 5).getFirst();
        assertThat(ledger(third.plus(LEASE)).claim(tenantA.id(), INSTANCE_B, 5)).isEmpty();
        assertThat(item(tenantA, item)).satisfies(work -> {
            assertThat(work.state()).isEqualTo(FollowUpWorkState.EXHAUSTED);
            assertThat(work.lastFailure()).contains(FollowUpWorkFailure.LEASE_EXPIRED);
        });
        assertThatThrownBy(() -> inContext(ownerA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.REQUEUE, 0)))
                .isInstanceOfSatisfying(FollowUpWorkRecoveryService.RecoveryRefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(Refusal.OUTCOME_UNKNOWN));
        assertThat(inContext(ownerA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.RESOLVE, 0)).applied())
                .isTrue();
    }

    @Test
    void aStaleHolderRecordsNothingOverTheNewerClaim() throws Exception {
        UUID item = oneItem();
        // The holder works past its lease without renewing in time; another instance takes the item meanwhile.
        var summary = processor(now).process(tenantA.id(), INSTANCE_A, 5, lease -> {
            FollowUpWorkClaim takeover = ledger(now.plus(LEASE)).claim(tenantA.id(), INSTANCE_B, 5).getFirst();
            assertThat(takeover.attempt()).isEqualTo(2);
            assertThat(lease.renew()).isFalse();
            return new FollowUpWorkAttemptResult.OutcomeUnknown();
        });

        assertThat(summary.stale()).isEqualTo(1);
        assertThat(summary.recoveryRequired()).isZero();
        assertThat(item(tenantA, item)).satisfies(work -> {
            assertThat(work.state()).isEqualTo(FollowUpWorkState.CLAIMED);
            assertThat(work.claimedBy()).contains(INSTANCE_B);
            assertThat(work.lastFailure()).isEmpty();
        });
    }

    @Test
    void recoveryIsIdempotentVersionCheckedAndBounded() throws Exception {
        UUID item = exhaustedBySafeFailures();

        var first = inContext(operatorA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.REQUEUE, 0));
        var replay = inContext(operatorA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.REQUEUE, 0));
        assertThat(first.applied()).isTrue();
        assertThat(replay.applied()).isFalse();
        assertThat(replay.item().recoveryCount()).isEqualTo(1);
        assertThat(auditRows(tenantA, "FOLLOW_UP_WORK_REQUEUED", first.item().customerId().value())).isEqualTo(1);
        // Another decision at the old version is stale, and an item that is not exhausted takes no decision.
        assertRefused(operatorA, item, FollowUpWorkRecoveryAction.RESOLVE, 0, Refusal.STALE_VERSION);
        assertRefused(operatorA, item, FollowUpWorkRecoveryAction.RESOLVE, 1, Refusal.NOT_EXHAUSTED);

        // Requeues stop at the configured bound (3 by default); resolving stays possible.
        Instant at = now.plus(Duration.ofHours(1));
        for (int requeue = 1; requeue < 3; requeue++) {
            at = exhaust(at);
            int expected = requeue;
            inContext(operatorA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.REQUEUE, expected));
        }
        exhaust(at);
        assertRefused(operatorA, item, FollowUpWorkRecoveryAction.REQUEUE, 3, Refusal.REQUEUE_LIMIT_REACHED);
        assertThat(inContext(operatorA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.RESOLVE, 3))
                .item().state()).isEqualTo(FollowUpWorkState.COMPLETED);
    }

    @Test
    void aRequeueIsRefusedWhileTheCustomerHasNewerOpenWork() throws Exception {
        Customer customer = dueCustomer("Newer work customer", today.minusDays(2));
        generate(tenantA);
        ledger(now).enqueue(tenantA.id());
        exhaust(now);
        UUID exhausted = onlyItem(tenantA).candidateId();

        // A safe exhaustion released the customer, so a newer cycle opened its own item.
        inContext(ownerA, () -> followUps.configureCustomer(customer.id(), 7, today,
                followUps.customerPolicy(customer.id()).version()));
        generate(tenantA);
        assertThat(ledger(now.plus(Duration.ofHours(1))).enqueue(tenantA.id())).isEqualTo(1);

        assertRefused(ownerA, exhausted, FollowUpWorkRecoveryAction.REQUEUE, 0, Refusal.CUSTOMER_HAS_NEWER_WORK);
        assertThat(item(tenantA, exhausted).state()).isEqualTo(FollowUpWorkState.EXHAUSTED);
    }

    @Test
    void aRequeueIsRefusedOnceANewerCycleWasWorkedAndCompleted() throws Exception {
        Customer customer = dueCustomer("Superseded customer", today.minusDays(2));
        generate(tenantA);
        ledger(now).enqueue(tenantA.id());
        exhaust(now);
        UUID exhausted = onlyItem(tenantA).candidateId();

        inContext(ownerA, () -> followUps.configureCustomer(customer.id(), 7, today,
                followUps.customerPolicy(customer.id()).version()));
        generate(tenantA);
        Instant newer = now.plus(Duration.ofHours(1));
        assertThat(ledger(newer).enqueue(tenantA.id())).isEqualTo(1);
        assertThat(processor(newer).process(tenantA.id(), INSTANCE_A, 5, COMPLETES).completed()).isEqualTo(1);

        assertRefused(ownerA, exhausted, FollowUpWorkRecoveryAction.REQUEUE, 0, Refusal.CUSTOMER_HAS_NEWER_WORK);
        AtomicInteger handled = new AtomicInteger();
        processor(newer.plusSeconds(60)).process(tenantA.id(), INSTANCE_B, 5, lease -> {
            handled.incrementAndGet();
            return new FollowUpWorkAttemptResult.Completed();
        });
        assertThat(handled).hasValue(0);
        assertThat(item(tenantA, exhausted).state()).isEqualTo(FollowUpWorkState.EXHAUSTED);
        // Resolving the superseded item stays possible.
        assertThat(inContext(ownerA, () -> recovery.recover(exhausted, FollowUpWorkRecoveryAction.RESOLVE, 0))
                .applied()).isTrue();
    }

    @Test
    void recoveryIsTenantIsolatedAndNeedsTheFollowUpPermissions() throws Exception {
        UUID item = exhaustedBySafeFailures();

        assertThat(inContext(ownerB, () -> recovery.recoverable(10))).isEmpty();
        assertThatThrownBy(() -> inContext(ownerB, () -> recovery.recover(item, FollowUpWorkRecoveryAction.RESOLVE, 0)))
                .isInstanceOf(FollowUpWorkRecoveryService.RecoverableWorkNotFoundException.class);
        assertThatThrownBy(() -> inContext(viewerA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.RESOLVE,
                0))).isInstanceOf(TenantAccessDeniedException.class);
        assertThat(inContext(viewerA, () -> recovery.recoverable(10))).hasSize(1);
        assertThat(item(tenantA, item).state()).isEqualTo(FollowUpWorkState.EXHAUSTED);
        // Without a tenant context nothing is readable or writable.
        assertThatThrownBy(() -> recovery.recoverable(10)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void noAttemptIsSpentWhileProvidersAreUnavailable() throws Exception {
        UUID item = oneItem();
        FollowUpWorkProcessor down = new FollowUpWorkProcessor(ledger(now), retries(), health(
                FollowUpProviderHealth.Availability.UNAVAILABLE), Clock.fixed(now, ZoneOffset.UTC));

        assertThat(down.process(tenantA.id(), INSTANCE_A, 5, COMPLETES).deferred()).isTrue();
        assertThat(item(tenantA, item)).satisfies(work -> {
            assertThat(work.state()).isEqualTo(FollowUpWorkState.PENDING);
            assertThat(work.attempts()).isZero();
        });
    }

    @Test
    void theRepositoryNeverRequeuesAnUnknownOutcome() throws Exception {
        oneItem();
        processor(now).process(tenantA.id(), INSTANCE_A, 5, lease -> new FollowUpWorkAttemptResult.OutcomeUnknown());
        UUID item = onlyItem(tenantA).candidateId();
        Boolean requeued = contexts.callWithTenantId(tenantA.id(), () -> new TransactionTemplate(transactionManager)
                .execute(status -> repository.requeue(tenantA.id(), item, 0, now)));
        assertThat(requeued).isFalse();
        assertThat(onlyItem(tenantA).state()).isEqualTo(FollowUpWorkState.EXHAUSTED);
    }

    @Test
    void aRequeuedItemMayStillEndWithAnUnknownOutcomeAndBeResolved() throws Exception {
        UUID item = exhaustedBySafeFailures();
        inContext(ownerA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.REQUEUE, 0));

        Instant later = now.plus(Duration.ofHours(1));
        assertThat(processor(later).process(tenantA.id(), INSTANCE_A, 5, lease -> {
            throw new IllegalStateException("the send may have reached the provider");
        }).recoveryRequired()).isEqualTo(1);
        assertThat(item(tenantA, item)).satisfies(work -> {
            assertThat(work.state()).isEqualTo(FollowUpWorkState.EXHAUSTED);
            assertThat(work.lastFailure()).contains(FollowUpWorkFailure.OUTCOME_UNKNOWN);
        });

        assertRefused(ownerA, item, FollowUpWorkRecoveryAction.REQUEUE, 1, Refusal.OUTCOME_UNKNOWN);
        assertThat(inContext(ownerA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.RESOLVE, 1))
                .item().state()).isEqualTo(FollowUpWorkState.COMPLETED);
    }

    @Test
    void aRequeuedItemWhoseLastAttemptCrashesNeedsRecoveryAgain() throws Exception {
        UUID item = exhaustedBySafeFailures();
        inContext(ownerA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.REQUEUE, 0));

        // Two more safe failures, then the third attempt of the new budget crashes.
        Instant at = now.plus(Duration.ofHours(1));
        for (int attempt = 0; attempt < 2; attempt++) {
            processor(at).process(tenantA.id(), INSTANCE_A, 5, UNAVAILABLE);
            at = at.plus(Duration.ofMinutes(10));
        }
        assertThat(ledger(at).claim(tenantA.id(), INSTANCE_A, 5)).singleElement()
                .satisfies(claim -> assertThat(claim.attempt()).isEqualTo(6));
        assertThat(ledger(at.plus(LEASE)).claim(tenantA.id(), INSTANCE_B, 5)).isEmpty();

        assertThat(item(tenantA, item)).satisfies(work -> {
            assertThat(work.state()).isEqualTo(FollowUpWorkState.EXHAUSTED);
            assertThat(work.lastFailure()).contains(FollowUpWorkFailure.LEASE_EXPIRED);
        });
        assertThat(inContext(ownerA, () -> recovery.recoverable(10))).singleElement()
                .satisfies(view -> assertThat(view.outcomeUnknown()).isTrue());
        assertRefused(ownerA, item, FollowUpWorkRecoveryAction.REQUEUE, 1, Refusal.OUTCOME_UNKNOWN);
        assertThat(inContext(ownerA, () -> recovery.recover(item, FollowUpWorkRecoveryAction.RESOLVE, 1)).applied())
                .isTrue();
    }

    private void assertRefused(TenantContext member, UUID item, FollowUpWorkRecoveryAction action, int expected,
            Refusal refusal) {
        assertThatThrownBy(() -> inContext(member, () -> recovery.recover(item, action, expected)))
                .isInstanceOfSatisfying(FollowUpWorkRecoveryService.RecoveryRefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(refusal));
    }

    /** One due customer with one enqueued item. */
    private UUID oneItem() throws Exception {
        dueCustomer("Recovery customer", today);
        generate(tenantA);
        assertThat(ledger(now).enqueue(tenantA.id())).isEqualTo(1);
        return onlyItem(tenantA).candidateId();
    }

    private UUID exhaustedBySafeFailures() throws Exception {
        UUID item = oneItem();
        exhaust(now);
        return item;
    }

    /** Fails every remaining attempt of the tenant's claimable item from {@code from}; returns the instant after. */
    private Instant exhaust(Instant from) {
        Instant at = from;
        for (int attempt = 0; attempt < 3; attempt++) {
            processor(at).process(tenantA.id(), INSTANCE_A, 5, UNAVAILABLE);
            at = at.plus(Duration.ofMinutes(10));
        }
        return at;
    }

    private FollowUpWorkProcessor processor(Instant instant) {
        return new FollowUpWorkProcessor(ledger(instant), retries(),
                health(FollowUpProviderHealth.Availability.HEALTHY), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static FollowUpRetryPolicy retries() {
        return new FollowUpRetryPolicy(new FollowUpRetryProperties(INITIAL_BACKOFF, Duration.ofMinutes(5), 0.2, null));
    }

    private static ObjectProvider<FollowUpProviderHealth> health(
            FollowUpProviderHealth.Availability availability) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("health", (FollowUpProviderHealth) tenant -> availability);
        return beans.getBeanProvider(FollowUpProviderHealth.class);
    }

    private FollowUpWorkLedger ledger(Instant instant) {
        return new FollowUpWorkLedger(repository, contexts, new FollowUpWorkProperties(20, LEASE, 3),
                transactionManager, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private void generate(Tenant tenant) {
        new FollowUpCandidateGenerator(candidates, tenants, contexts, new FollowUpCandidateProperties(100),
                transactionManager, Clock.fixed(now, ZoneOffset.UTC)).generate(tenant.id());
    }

    private Customer dueCustomer(String name, LocalDate due) throws Exception {
        Customer customer = inContext(ownerA, () -> customers.create(name, null, List.of(new PhoneInput(
                "8888" + String.format("%04d", Math.floorMod(UUID.randomUUID().hashCode(), 10000)), "CR", true))));
        var policy = inContext(ownerA, () -> contacts.get(customer.id()));
        UUID contactId = customer.phones().getFirst().id();
        inContext(ownerA, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
        inContext(ownerA, () -> followUps.configureCustomer(customer.id(), 7, due, 0));
        return customer;
    }

    private FollowUpWorkItem onlyItem(Tenant tenant) {
        List<FollowUpWorkItem> items = contexts.callWithTenantId(tenant.id(), () -> repository.findItems(tenant.id(),
                100));
        assertThat(items).hasSize(1);
        return items.getFirst();
    }

    private FollowUpWorkItem item(Tenant tenant, UUID candidateId) {
        return contexts.callWithTenantId(tenant.id(), () -> repository.findItems(tenant.id(), 100)).stream()
                .filter(work -> work.candidateId().equals(candidateId)).findFirst().orElseThrow();
    }

    private TenantContext member(Tenant tenant, TenantRole role) {
        return context(seedMembership(memberships, contexts, tenant.id(), new IdentityId(UUID.randomUUID()), role,
                now));
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }

    private static int auditRows(Tenant tenant, String eventType, UUID customerId) throws Exception {
        try (Connection migration = TenantSecurityIntegrationFixture.migrationConnection()) {
            return TenantSecurityIntegrationFixture.queryInt(migration, """
                    SELECT count(*) FROM dokene.audit_events
                    WHERE tenant_id = ? AND event_type = ? AND target_type = 'CUSTOMER' AND target_id = ?
                      AND outcome = 'SUCCESS' AND actor_id IS NOT NULL AND membership_id IS NOT NULL
                    """, tenant.id().value(), eventType, customerId);
        }
    }
}
