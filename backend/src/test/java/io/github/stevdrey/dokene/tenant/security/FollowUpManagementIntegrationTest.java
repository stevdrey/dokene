package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.FollowUpPolicyRepository;
import io.github.stevdrey.dokene.followup.application.FollowUpConflictException;
import io.github.stevdrey.dokene.followup.application.FollowUpQueuePage;
import io.github.stevdrey.dokene.followup.application.FollowUpQueueQuery;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class FollowUpManagementIntegrationTest {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    @Autowired FollowUpService followUps;
    @Autowired FollowUpPolicyRepository policies;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired CustomerService customers;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired AuditReader auditReader;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuditRecorder auditRecorder;

    private TenantContext contextA;
    private TenantContext contextB;
    private Customer customer;

    @BeforeEach
    void setUp() throws Exception {
        Instant now = Instant.now();
        var tenantA = seedTenant(tenants, "Follow-up A " + UUID.randomUUID(), now);
        var tenantB = seedTenant(tenants, "Follow-up B " + UUID.randomUUID(), now);
        contextA = context(seedMembership(memberships, contexts, tenantA.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
        contextB = context(seedMembership(memberships, contexts, tenantB.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
        customer = inContext(contextA, () -> customers.create("Follow-up customer", null,
                List.of(new PhoneInput("8888" + String.format("%04d",
                        Math.abs(UUID.randomUUID().hashCode()) % 10000), "CR", true))));
    }

    @Test
    void reflectsCurrentPurchaseAndConsentAndIsolatesPolicyAccess() throws Exception {
        var noConsent = inContext(contextA, () -> followUps.evaluate(customer.id()));
        assertThat(noConsent.reasons()).contains(FollowUpReason.NO_ELIGIBLE_CONTACT);

        var policy = inContext(contextA, () -> contacts.get(customer.id()));
        UUID contactId = customer.phones().getFirst().id();
        inContext(contextA, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
        inContext(contextA, () -> purchases.record(customer.id(), Instant.now().minusSeconds(1),
                "Follow-up anchor", "follow-up-anchor"));
        inContext(contextA, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        inContext(contextA, () -> followUps.configureCustomer(customer.id(), null,
                LocalDate.now(ZoneId.of("UTC")), 0));

        var due = inContext(contextA, () -> followUps.evaluate(customer.id()));
        assertThat(due.status()).isEqualTo(FollowUpStatus.DUE);

        var current = inContext(contextA, () -> contacts.get(customer.id()));
        inContext(contextA, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.REVOKED, ContactIntentSource.CUSTOMER_VERBAL, current.version()));
        assertThat(inContext(contextA, () -> followUps.evaluate(customer.id())).reasons())
                .contains(FollowUpReason.NO_ELIGIBLE_CONTACT);
        assertThatThrownBy(() -> inContext(contextB, () -> followUps.evaluate(customer.id())))
                .isInstanceOf(CustomerNotFoundException.class);
    }

    @Test
    void oneConcurrentWriterWinsAndOwnedColumnsAreNotRestored() throws Exception {
        grantWhatsAppConsent(customer);
        inContext(contextA, () -> followUps.configureCustomer(customer.id(), 9, LocalDate.now(), 0));
        var start = new CountDownLatch(1);
        var successes = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentUpdate(start, successes, 10));
            var second = executor.submit(() -> concurrentUpdate(start, successes, 11));
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        assertThat(successes).hasValue(1);
        var policy = inContext(contextA, () -> followUps.customerPolicy(customer.id()));
        assertThat(policy.version()).isEqualTo(2);
        assertThat(policy.cadenceDays()).isIn(10, 11);

        var snoozed = inContext(contextA, () -> followUps.snooze(customer.id(), LocalDate.now().plusDays(2), 2));
        var configured = inContext(contextA, () -> followUps.configureCustomer(customer.id(), 12,
                LocalDate.now().plusDays(1), snoozed.version()));
        assertThat(configured.snoozedUntil()).isEqualTo(LocalDate.now().plusDays(2));
    }

    @Test
    void manualCompletionIsIdempotentAndAuditsEachTransitionOnce() throws Exception {
        grantWhatsAppConsent(customer);
        var tenant = inContext(contextA, () -> followUps.configureTenant(14, ZoneId.of("America/Costa_Rica"), 0));
        var configured = inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7,
                LocalDate.now(), 0));
        var snoozed = inContext(contextA, () -> followUps.snooze(customer.id(), LocalDate.now().plusDays(1),
                configured.version()));
        var first = inContext(contextA, () -> followUps.recordManualFollowUp(customer.id(), snoozed.version(),
                "manual-integration-1", "Spoke with client"));
        var replay = inContext(contextA, () -> followUps.recordManualFollowUp(customer.id(), 0,
                "manual-integration-1", "Spoke with client"));
        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.completion()).isEqualTo(first.completion());
        assertThat(replay.completion().notes()).isEqualTo("Spoke with client");

        var other = inContext(contextA, () -> customers.create("Other customer", null,
                List.of(new PhoneInput("8777" + String.format("%04d",
                        Math.abs(UUID.randomUUID().hashCode()) % 10000), "CR", true))));
        grantWhatsAppConsent(other);
        assertThatThrownBy(() -> inContext(contextA, () -> followUps.recordManualFollowUp(other.id(), 0,
                "manual-integration-1"))).isInstanceOf(FollowUpConflictException.class);
        assertThatThrownBy(() -> inContext(contextA, () -> followUps.recordManualFollowUp(customer.id(), 0,
                "manual-stale-new-key"))).isInstanceOf(FollowUpConflictException.class);
        inContext(contextA, () -> {
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM dokene.manual_follow_up_completions WHERE idempotency_key = ?
                    """, Integer.class, "manual-stale-new-key")).isZero();
            var events = auditReader.read(null, 100).events();
            assertThat(events).filteredOn(event -> event.target() != null
                    && event.target().id().equals(customer.id().value()))
                    .extracting(event -> event.type()).contains(
                            AuditEventType.CUSTOMER_FOLLOW_UP_POLICY_CHANGED,
                            AuditEventType.FOLLOW_UP_SNOOZED,
                            AuditEventType.MANUAL_FOLLOW_UP_RECORDED);
            assertThat(events).filteredOn(event -> event.type() == AuditEventType.MANUAL_FOLLOW_UP_RECORDED)
                    .hasSize(1).allSatisfy(event -> {
                        assertThat(event.actorId()).isEqualTo(contextA.identityId());
                        assertThat(event.membershipId()).isEqualTo(contextA.membershipId());
                    });
            assertThat(events).filteredOn(event -> event.type() == AuditEventType.TENANT_FOLLOW_UP_POLICY_CHANGED)
                    .hasSize(1).allSatisfy(event -> assertThat(event.target().id()).isEqualTo(tenant.tenantId().value()));
            return null;
        });
    }

    @Test
    void auditFailureRollsBackPolicySnoozeAndCompletion() throws Exception {
        grantWhatsAppConsent(customer);
        inContext(contextA, () -> followUps.configureCustomer(customer.id(), 8, LocalDate.now(), 0));

        doThrow(new AuditPersistenceException()).when(auditRecorder)
                .followUpMutated(any(), any(), any());

        assertThatThrownBy(() -> inContext(contextA,
                () -> followUps.configureCustomer(customer.id(), 10, LocalDate.now(), 1)))
                .isInstanceOf(AuditPersistenceException.class);
        assertThat(inContext(contextA, () -> followUps.customerPolicy(customer.id())).cadenceDays()).isEqualTo(8);

        assertThatThrownBy(() -> inContext(contextA,
                () -> followUps.snooze(customer.id(), LocalDate.now().plusDays(1), 1)))
                .isInstanceOf(AuditPersistenceException.class);
        assertThat(inContext(contextA, () -> followUps.customerPolicy(customer.id())).snoozedUntil()).isNull();

        assertThatThrownBy(() -> inContext(contextA,
                () -> followUps.recordManualFollowUp(customer.id(), 1, "audit-failure-manual")))
                .isInstanceOf(AuditPersistenceException.class);
        inContext(contextA, () -> {
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM dokene.manual_follow_up_completions WHERE idempotency_key = ?
                    """, Integer.class, "audit-failure-manual")).isZero();
            return null;
        });
    }

    @Test
    void queueReturnsDueCustomersAndIsolatesTenants() throws Exception {
        grantWhatsAppConsent(customer);
        inContext(contextA, () -> followUps.configureCustomer(customer.id(), 14, LocalDate.now(), 0));

        var customerA2 = inContext(contextA, () -> customers.create("Customer A2", null,
                List.of(new PhoneInput("8881" + String.format("%04d",
                        Math.abs(UUID.randomUUID().hashCode()) % 10000), "CR", true))));

        var customerB = inContext(contextB, () -> customers.create("Customer B", null,
                List.of(new PhoneInput("8882" + String.format("%04d",
                        Math.abs(UUID.randomUUID().hashCode()) % 10000), "CR", true))));
        var policyB = inContext(contextB, () -> contacts.get(customerB.id()));
        inContext(contextB, () -> contacts.changeConsent(customerB.id(), customerB.phones().getFirst().id(),
                ContactChannel.WHATSAPP, ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policyB.version()));
        inContext(contextB, () -> followUps.configureCustomer(customerB.id(), 14, LocalDate.now(), 0));

        FollowUpQueuePage queueA = inContext(contextA, () -> followUps.dueQueue(new FollowUpQueueQuery(null, null, 10)));
        assertThat(queueA.items()).extracting(item -> item.customerId()).contains(customer.id())
                .doesNotContain(customerA2.id(), customerB.id());

        FollowUpQueuePage queueB = inContext(contextB, () -> followUps.dueQueue(new FollowUpQueueQuery(null, null, 10)));
        assertThat(queueB.items()).extracting(item -> item.customerId()).contains(customerB.id())
                .doesNotContain(customer.id(), customerA2.id());
    }

    @Test
    void dismissAdvancesCadenceCycleAndClearsSnooze() throws Exception {
        grantWhatsAppConsent(customer);
        inContext(contextA, () -> followUps.configureTenant(14, ZoneId.of("America/Costa_Rica"), 0));
        var configured = inContext(contextA, () -> followUps.configureCustomer(customer.id(), 7, LocalDate.now(), 0));
        var snoozed = inContext(contextA, () -> followUps.snooze(customer.id(), LocalDate.now(), configured.version()));

        var first = inContext(contextA, () -> followUps.dismiss(customer.id(), snoozed.version(),
                "dismiss-integration-1", "Dismissed for now"));
        assertThat(first.created()).isTrue();
        assertThat(first.dismissal().notes()).isEqualTo("Dismissed for now");

        var policy = inContext(contextA, () -> followUps.customerPolicy(customer.id()));
        assertThat(policy.snoozedUntil()).isNull();
        assertThat(policy.explicitNextDate()).isNull();
        assertThat(policy.lastDismissedDate()).isEqualTo(LocalDate.now(ZoneId.of("America/Costa_Rica")));

        var eval = inContext(contextA, () -> followUps.evaluate(customer.id()));
        assertThat(eval.status()).isEqualTo(FollowUpStatus.NOT_YET_DUE);
        assertThat(eval.timingSource()).isEqualTo(FollowUpTimingSource.LAST_DISMISSAL);
        assertThat(eval.nextFollowUpDate()).isEqualTo(LocalDate.now(ZoneId.of("America/Costa_Rica")).plusDays(7));

        FollowUpQueuePage queue = inContext(contextA, () -> followUps.dueQueue(new FollowUpQueueQuery(null, null, 10)));
        assertThat(queue.items()).extracting(item -> item.customerId()).doesNotContain(customer.id());

        var replay = inContext(contextA, () -> followUps.dismiss(customer.id(), 0,
                "dismiss-integration-1", "Different note"));
        assertThat(replay.created()).isFalse();
        assertThat(replay.dismissal()).isEqualTo(first.dismissal());

        inContext(contextA, () -> {
            var events = auditReader.read(null, 100).events();
            assertThat(events).filteredOn(event -> event.type() == AuditEventType.FOLLOW_UP_DISMISSED)
                    .hasSize(1).allSatisfy(event -> {
                        assertThat(event.actorId()).isEqualTo(contextA.identityId());
                        assertThat(event.membershipId()).isEqualTo(contextA.membershipId());
                    });
            return null;
        });
    }

    @Test
    void revokedConsentOrArchivedCustomerDropsFromQueueAndRejectsDispositions() throws Exception {
        grantWhatsAppConsent(customer);
        inContext(contextA, () -> followUps.configureCustomer(customer.id(), 14, LocalDate.now(), 0));

        FollowUpQueuePage initialQueue = inContext(contextA, () -> followUps.dueQueue(new FollowUpQueueQuery(null, null, 10)));
        assertThat(initialQueue.items()).extracting(item -> item.customerId()).contains(customer.id());

        // Revoke consent
        var contactPolicy = inContext(contextA, () -> contacts.get(customer.id()));
        UUID contactId = customer.phones().getFirst().id();
        inContext(contextA, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.REVOKED, ContactIntentSource.CUSTOMER_VERBAL, contactPolicy.version()));

        FollowUpQueuePage queueAfterRevoke = inContext(contextA, () -> followUps.dueQueue(new FollowUpQueueQuery(null, null, 10)));
        assertThat(queueAfterRevoke.items()).extracting(item -> item.customerId()).doesNotContain(customer.id());

        assertThatThrownBy(() -> inContext(contextA, () -> followUps.snooze(customer.id(), LocalDate.now().plusDays(2), 1)))
                .isInstanceOf(FollowUpConflictException.class);
        assertThatThrownBy(() -> inContext(contextA, () -> followUps.dismiss(customer.id(), 1, "key-revoked", null)))
                .isInstanceOf(FollowUpConflictException.class);
        assertThatThrownBy(() -> inContext(contextA, () -> followUps.recordManualFollowUp(customer.id(), 1, "key-revoked", null)))
                .isInstanceOf(FollowUpConflictException.class);

        // Grant consent back
        var contactPolicy2 = inContext(contextA, () -> contacts.get(customer.id()));
        inContext(contextA, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, contactPolicy2.version()));
        assertThat(inContext(contextA, () -> followUps.dueQueue(new FollowUpQueueQuery(null, null, 10))).items())
                .extracting(item -> item.customerId()).contains(customer.id());

        // Archive customer
        var currentCustomer = inContext(contextA, () -> customers.get(customer.id()));
        inContext(contextA, () -> {
            customers.archive(customer.id(), currentCustomer.version());
            return null;
        });

        FollowUpQueuePage queueAfterArchive = inContext(contextA, () -> followUps.dueQueue(new FollowUpQueueQuery(null, null, 10)));
        assertThat(queueAfterArchive.items()).extracting(item -> item.customerId()).doesNotContain(customer.id());

        assertThatThrownBy(() -> inContext(contextA, () -> followUps.snooze(customer.id(), LocalDate.now().plusDays(2), 1)))
                .isInstanceOf(FollowUpConflictException.class);
    }

    @Test
    void databaseLevelEligibilityPredicatesRejectDispositionsOnIneligibleCustomers() throws Exception {
        // Customer has no WhatsApp consent yet
        var policy = inContext(contextA, () -> followUps.customerPolicy(customer.id()));
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        assertThatThrownBy(() -> inContext(contextA, () -> {
            policies.snooze(customer.tenantId(), customer.id(), tomorrow, policy.version());
            return null;
        })).isInstanceOf(FollowUpConflictException.class);

        assertThatThrownBy(() -> inContext(contextA, () -> {
            policies.recordDismissal(customer.tenantId(), customer.id(), tomorrow, policy.version(),
                    "direct-db-dismiss", Instant.now(), contextA.identityId(), contextA.membershipId(), null);
            return null;
        })).isInstanceOf(FollowUpConflictException.class);

        assertThatThrownBy(() -> inContext(contextA, () -> {
            policies.recordManualFollowUp(customer.tenantId(), customer.id(), tomorrow, policy.version(),
                    "direct-db-manual", Instant.now(), contextA.identityId(), contextA.membershipId(), null);
            return null;
        })).isInstanceOf(FollowUpConflictException.class);
    }

    @Test
    void timeTransitionsClassifyOverdueAndDue() throws Exception {
        grantWhatsAppConsent(customer);
        inContext(contextA, () -> followUps.configureCustomer(customer.id(), 14, LocalDate.now(), 0));

        var overdueCustomer = inContext(contextA, () -> customers.create("Overdue customer", null,
                List.of(new PhoneInput("8666" + String.format("%04d",
                        Math.abs(UUID.randomUUID().hashCode()) % 10000), "CR", true))));
        grantWhatsAppConsent(overdueCustomer);
        inContext(contextA, () -> followUps.configureCustomer(overdueCustomer.id(), 14, LocalDate.now().minusDays(3), 0));

        FollowUpQueuePage overdueQueue = inContext(contextA, () -> followUps.dueQueue(new FollowUpQueueQuery(FollowUpStatus.OVERDUE, null, 10)));
        assertThat(overdueQueue.items()).extracting(item -> item.customerId())
                .containsExactly(overdueCustomer.id());
        assertThat(overdueQueue.items().getFirst().status()).isEqualTo(FollowUpStatus.OVERDUE);

        FollowUpQueuePage dueQueue = inContext(contextA, () -> followUps.dueQueue(new FollowUpQueueQuery(FollowUpStatus.DUE, null, 10)));
        assertThat(dueQueue.items()).extracting(item -> item.customerId())
                .containsExactly(customer.id());
        assertThat(dueQueue.items().getFirst().status()).isEqualTo(FollowUpStatus.DUE);

        FollowUpQueuePage allQueue = inContext(contextA, () -> followUps.dueQueue(new FollowUpQueueQuery(null, null, 10)));
        assertThat(allQueue.items()).extracting(item -> item.customerId())
                .containsExactly(overdueCustomer.id(), customer.id());
    }

    private void grantWhatsAppConsent(Customer target) throws Exception {
        var policy = inContext(contextA, () -> contacts.get(target.id()));
        UUID contactId = target.phones().getFirst().id();
        inContext(contextA, () -> contacts.changeConsent(target.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
    }

    private void concurrentUpdate(CountDownLatch start, AtomicInteger successes, int cadence) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent writers did not start");
            inContext(contextA, () -> followUps.configureCustomer(customer.id(), cadence, LocalDate.now(), 1));
            successes.incrementAndGet();
        } catch (FollowUpConflictException ignored) {
            // Exactly one request is expected to lose the optimistic update.
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }
}
