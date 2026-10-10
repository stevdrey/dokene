// ABOUTME: Pins the T1/T2 policy guards, the open message index backstop, same key races and cross tenant reads.
// ABOUTME: Written against the opus review of spec 0001; every test names the acceptance criterion it covers.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.messaging.application.ApprovalCommand;
import io.github.stevdrey.dokene.messaging.application.CommandResult;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageCommandService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.messaging.application.SubmitMessageCommand;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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

@SpringBootTest(properties = {"dokene.ai.provider=fake",
        "dokene.messaging.template-gate.stub.enabled-intents=GENERAL_FOLLOW_UP"})
class OutboundMessagingGuardIntegrationTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";
    private static final List<String> MESSAGING_TABLES = List.of("outbound_messages", "outbound_message_approvals",
            "outbound_message_cancellations", "outbound_send_attempts", "outbound_message_events",
            "outbound_message_idempotency_keys");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired OutboundMessageCommandService commands;
    @Autowired OutboundMessageRepository messages;
    @Autowired FollowUpService followUps;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired CustomerService customers;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired AuditReader auditReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private Tenant tenantA;
    private TenantContext ownerA;
    private TenantContext ownerB;
    private Customer customer;
    private UUID contactId;
    private long policyVersion;

    @BeforeEach
    void setUp() throws Exception {
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, "Guard A " + UUID.randomUUID(), now);
        Tenant tenantB = seedTenant(tenants, "Guard B " + UUID.randomUUID(), now);
        ownerA = context(seedMembership(memberships, contexts, tenantA.id(), new IdentityId(UUID.randomUUID()),
                TenantRole.OWNER, now));
        ownerB = context(seedMembership(memberships, contexts, tenantB.id(), new IdentityId(UUID.randomUUID()),
                TenantRole.OWNER, now));
        inContext(ownerA, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        customer = eligibleCustomer("Guard customer");
        contactId = customer.phones().getFirst().id();
        policyVersion = inContext(ownerA, () -> followUps.evaluateSnapshot(customer.id())).policyVersion();
    }

    /** covers: AC-4 (T1 guard steps 3 to 6 refuse before any row is written). */
    @Test
    void submitRefusesArchivedDoNotContactUnconsentedAndNotYetDueCustomersWithoutRows() throws Exception {
        Customer archived = eligibleCustomer("Archived customer");
        inContext(ownerA, () -> {
            customers.archive(archived.id(), customers.get(archived.id()).version());
            return null;
        });
        assertThatRefused(() -> inContext(ownerA, () -> commands.submit(
                submit(archived, archived.phones().getFirst().id(), "ar-" + UUID.randomUUID()))),
                MessagingErrorCode.CUSTOMER_ARCHIVED);

        Customer silenced = eligibleCustomer("Do not contact customer");
        inContext(ownerA, () -> contacts.changeDoNotContact(silenced.id(), true,
                ContactIntentSource.CUSTOMER_VERBAL, contacts.get(silenced.id()).version()));
        assertThatRefused(() -> inContext(ownerA, () -> commands.submit(
                submit(silenced, silenced.phones().getFirst().id(), "dnc-" + UUID.randomUUID()))),
                MessagingErrorCode.DO_NOT_CONTACT);

        // A second phone without consent is refused even though the first phone of the same customer has it.
        Customer withSecondPhone = inContext(ownerA, () -> customers.update(customer.id(),
                customers.get(customer.id()).version(), customer.displayName(), customer.notes(),
                List.of(new PhoneInput(customer.phones().getFirst().e164(), "CR", true),
                        new PhoneInput(uniqueLocalNumber(), "CR", false))));
        UUID unconsented = withSecondPhone.phones().stream().filter(phone -> !phone.primary()).findFirst()
                .orElseThrow().id();
        assertThatRefused(() -> inContext(ownerA, () -> commands.submit(
                submit(withSecondPhone, unconsented, "nc-" + UUID.randomUUID()))),
                MessagingErrorCode.NO_CONTACT_CONSENT);

        Customer notDue = eligibleCustomer("Not yet due customer");
        inContext(ownerA, () -> followUps.configureCustomer(notDue.id(), null,
                LocalDate.now(ZoneId.of("UTC")).plusDays(45),
                followUps.evaluateSnapshot(notDue.id()).policyVersion()));
        assertThat(inContext(ownerA, () -> followUps.evaluateSnapshot(notDue.id())).evaluation().status())
                .isEqualTo(FollowUpStatus.NOT_YET_DUE);
        assertThatRefused(() -> inContext(ownerA, () -> commands.submit(
                submit(notDue, notDue.phones().getFirst().id(), "nd-" + UUID.randomUUID()))),
                MessagingErrorCode.FOLLOW_UP_INELIGIBLE);

        for (String table : MESSAGING_TABLES) {
            assertThat(rowCount(ownerA, table)).as(table).isZero();
        }
    }

    /** covers: AC-4 (T2 re-runs the shared guards: a consent change between submit and approve blocks approval). */
    @Test
    void approveReChecksConsentAndDoNotContactAndWritesNothingWhenRefused() throws Exception {
        OutboundMessage message = inContext(ownerA, () -> commands.submit(
                submit(customer, contactId, "s-" + UUID.randomUUID()))).message();
        assertThat(message.status()).isEqualTo(MessageStatus.PENDING_APPROVAL);

        inContext(ownerA, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.REVOKED, ContactIntentSource.CUSTOMER_WRITTEN, contacts.get(customer.id()).version()));
        assertThatRefused(() -> inContext(ownerA, () -> commands.approve(
                new ApprovalCommand(message.id(), null, "1", "a1-" + UUID.randomUUID()))),
                MessagingErrorCode.NO_CONTACT_CONSENT);

        inContext(ownerA, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, contacts.get(customer.id()).version()));
        inContext(ownerA, () -> contacts.changeDoNotContact(customer.id(), true,
                ContactIntentSource.CUSTOMER_VERBAL, contacts.get(customer.id()).version()));
        assertThatRefused(() -> inContext(ownerA, () -> commands.approve(
                new ApprovalCommand(message.id(), null, "1", "a2-" + UUID.randomUUID()))),
                MessagingErrorCode.DO_NOT_CONTACT);

        OutboundMessage unchanged = inContext(ownerA, () -> messages.findById(tenantA.id().value(), message.id()))
                .orElseThrow();
        assertThat(unchanged.status()).isEqualTo(MessageStatus.PENDING_APPROVAL);
        assertThat(unchanged.version()).isEqualTo(1);
        assertThat(inContext(ownerA, () -> messages.findEvents(tenantA.id().value(), message.id()))).hasSize(1);
        assertThat(rowCount(ownerA, "outbound_message_approvals")).isZero();
        assertThat(rowCount(ownerA, "outbound_message_idempotency_keys")).isEqualTo(1);
        assertThat(inContext(ownerA, () -> auditReader.read(null, 100).events()))
                .extracting(AuditEvent::type).doesNotContain(AuditEventType.MESSAGE_APPROVED);
    }

    /** covers: AC-2 (the partial unique index is the backstop when the service lock is bypassed). */
    @Test
    void openMessageIndexRefusesASecondOpenRowInsertedBehindTheService() throws Exception {
        OutboundMessage first = inContext(ownerA, () -> commands.submit(
                submit(customer, contactId, "i1-" + UUID.randomUUID()))).message();

        OutboundMessage bypass = OutboundMessage.submit(new OutboundMessage.Submission(UUID.randomUUID(),
                tenantA.id().value(), customer.id().value(), contactId, customer.phones().getFirst().e164(),
                SemanticAction.GENERAL_CHECK_IN, SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419",
                MessageOrigin.MANUAL, BODY, policyVersion, UUID.randomUUID(), ownerA.membershipId().value(),
                ownerA.identityId().value()), clock.instant()).next();

        assertThatRefused(() -> inContext(ownerA, () -> messages.insert(bypass)),
                MessagingErrorCode.MESSAGE_ALREADY_OPEN);
        assertThat(rowCount(ownerA, "outbound_messages")).isEqualTo(1);
        assertThat(inContext(ownerA, () -> messages.findById(tenantA.id().value(), first.id()))).isPresent();
    }

    /** covers: AC-5 (two writers replaying one key produce one row, one key record and one created result). */
    @Test
    void concurrentSubmitsWithTheSameKeyProduceOneMessageAndOneKeyRow() throws Exception {
        String key = "same-" + UUID.randomUUID();
        int writers = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<CommandResult>> outcomes = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                outcomes.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return inContext(ownerA, () -> commands.submit(submit(customer, contactId, key)));
                }));
            }
            start.countDown();
            List<CommandResult> results = new ArrayList<>();
            for (Future<CommandResult> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(CommandResult::created).hasSize(1);
            assertThat(results).extracting(result -> result.message().id()).containsOnly(results.getFirst().message().id());
        } finally {
            pool.shutdownNow();
        }
        assertThat(rowCount(ownerA, "outbound_messages")).isEqualTo(1);
        assertThat(rowCount(ownerA, "outbound_message_idempotency_keys")).isEqualTo(1);
        assertThat(rowCount(ownerA, "outbound_message_events")).isEqualTo(1);
    }

    /** covers: AC-8 (tenant B sees none of tenant A's messaging rows and cannot replay A's key). */
    @Test
    void anotherTenantSeesNoMessagingRowsAndCannotReplayTheKey() throws Exception {
        String key = "iso-" + UUID.randomUUID();
        inContext(ownerA, () -> commands.submit(submit(customer, contactId, key)));
        assertThat(rowCount(ownerA, "outbound_messages")).isEqualTo(1);

        for (String table : MESSAGING_TABLES) {
            assertThat(inContext(ownerB, () -> jdbc.queryForObject(
                    "SELECT count(*) FROM dokene." + table, Integer.class))).as(table).isZero();
        }
        assertThatRefused(() -> inContext(ownerB, () -> commands.submit(submit(customer, contactId, key))),
                MessagingErrorCode.NOT_FOUND);
        assertThat(rowCount(ownerA, "outbound_message_idempotency_keys")).isEqualTo(1);
    }

    /**
     * covers: spec 0001 trade-off "phone number corrections do not propagate to pending messages; operators must
     * cancel and resubmit", which assumes the correction itself still succeeds after a message exists.
     */
    @Test
    void correctingAPhoneNumberStillSucceedsAfterAMessageWasSentToIt() throws Exception {
        inContext(ownerA, () -> commands.submit(submit(customer, contactId, "ph-" + UUID.randomUUID())));

        assertThatCode(() -> inContext(ownerA, () -> customers.update(customer.id(),
                customers.get(customer.id()).version(), customer.displayName(), customer.notes(),
                List.of(new PhoneInput(uniqueLocalNumber(), "CR", true)))))
                .doesNotThrowAnyException();
    }

    private Customer eligibleCustomer(String name) throws Exception {
        Instant now = Instant.now();
        Customer created = inContext(ownerA, () -> customers.create(name, null,
                List.of(new PhoneInput(uniqueLocalNumber(), "CR", true))));
        UUID phone = created.phones().getFirst().id();
        inContext(ownerA, () -> contacts.changeConsent(created.id(), phone, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, contacts.get(created.id()).version()));
        inContext(ownerA, () -> purchases.record(created.id(), now.minusSeconds(60), "Anchor purchase",
                "anchor-" + UUID.randomUUID()));
        inContext(ownerA, () -> followUps.configureCustomer(created.id(), null, LocalDate.now(ZoneId.of("UTC")), 0));
        assertThat(inContext(ownerA, () -> followUps.evaluateSnapshot(created.id())).evaluation().status())
                .isEqualTo(FollowUpStatus.DUE);
        return created;
    }

    private static String uniqueLocalNumber() {
        return "8888" + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000);
    }

    private SubmitMessageCommand submit(Customer target, UUID contact, String idempotencyKey) throws Exception {
        long version = inContext(ownerA, () -> followUps.evaluateSnapshot(target.id())).policyVersion();
        return new SubmitMessageCommand(target.id().value(), contact, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP",
                BODY, "es-419", "MANUAL", Long.toString(version), idempotencyKey);
    }

    private int rowCount(TenantContext context, String table) throws Exception {
        return inContext(context, () -> jdbc.queryForObject(
                "SELECT count(*) FROM dokene." + table + " WHERE tenant_id = ?", Integer.class, tenantA.id().value()));
    }

    private static void assertThatRefused(Callable<?> operation, MessagingErrorCode code) {
        assertThatThrownBy(operation::call).isInstanceOf(MessagingRefusedException.class)
                .extracting(exception -> ((MessagingRefusedException) exception).code()).isEqualTo(code);
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }
}
