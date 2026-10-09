// ABOUTME: Drives spec 0001 end to end against Postgres: submit, approve, reject, cancel, send and delivery reports.
// ABOUTME: Proves idempotency, audit atomicity, tenant isolation, the follow up anchor, event chains and log hygiene.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpQueueQuery;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.FollowUpTouchRecorder;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.messaging.application.ApprovalCommand;
import io.github.stevdrey.dokene.messaging.application.CancellationCommand;
import io.github.stevdrey.dokene.messaging.application.CommandResult;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusResult;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusSink;
import io.github.stevdrey.dokene.messaging.application.MessageAuditPort;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageCommandService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageTestDriver;
import io.github.stevdrey.dokene.messaging.application.SubmitMessageCommand;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.ProviderContext;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"dokene.ai.provider=fake",
        "dokene.messaging.template-gate.stub.enabled-intents=GENERAL_FOLLOW_UP"})
class OutboundMessagingIntegrationTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";
    private static final String NOTE = "nota privada del operador";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        // Denied commands audit through an independent transaction, and the race test runs four writers.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired OutboundMessageCommandService commands;
    @Autowired OutboundMessageRepository messages;
    @Autowired MessageAuditPort messageAudit;
    @Autowired DeliveryStatusSink sink;
    @Autowired ProviderContextProvider providerContexts;
    @Autowired FollowUpService followUps;
    @Autowired FollowUpTouchRecorder touches;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired CustomerService customers;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired AuditReader auditReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;
    @MockitoSpyBean AuditRecorder auditRecorder;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger messagingLogger = (Logger) LoggerFactory.getLogger("io.github.stevdrey.dokene.messaging");

    private Tenant tenantA;
    private TenantContext ownerA;
    private TenantContext viewerA;
    private TenantContext ownerB;
    private Customer customer;
    private UUID contactId;
    private long policyVersion;
    private OutboundMessageTestDriver driver;

    @BeforeEach
    void setUp() throws Exception {
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, "Messaging A " + UUID.randomUUID(), now);
        Tenant tenantB = seedTenant(tenants, "Messaging B " + UUID.randomUUID(), now);
        ownerA = context(seedMembership(memberships, contexts, tenantA.id(), new IdentityId(UUID.randomUUID()),
                TenantRole.OWNER, now));
        viewerA = context(seedMembership(memberships, contexts, tenantA.id(), new IdentityId(UUID.randomUUID()),
                TenantRole.VIEWER, now));
        ownerB = context(seedMembership(memberships, contexts, tenantB.id(), new IdentityId(UUID.randomUUID()),
                TenantRole.OWNER, now));
        customer = inContext(ownerA, () -> customers.create("Messaging customer", null,
                List.of(new PhoneInput("8888" + String.format("%04d",
                        Math.abs(UUID.randomUUID().hashCode()) % 10000), "CR", true))));
        contactId = customer.phones().getFirst().id();
        var policy = inContext(ownerA, () -> contacts.get(customer.id()));
        inContext(ownerA, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
        inContext(ownerA, () -> purchases.record(customer.id(), now.minusSeconds(60), "Anchor purchase",
                "anchor-" + UUID.randomUUID()));
        inContext(ownerA, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        inContext(ownerA, () -> followUps.configureCustomer(customer.id(), null, LocalDate.now(ZoneId.of("UTC")), 0));
        var snapshot = inContext(ownerA, () -> followUps.evaluateSnapshot(customer.id()));
        assertThat(snapshot.evaluation().status()).isEqualTo(FollowUpStatus.DUE);
        policyVersion = snapshot.policyVersion();
        driver = new OutboundMessageTestDriver(messages, messageAudit, touches,
                new TransactionTemplate(transactionManager), clock);
        logs.start();
        messagingLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        messagingLogger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void submitsApprovesSendsAndAppliesDeliveryWhileAnchoringTheFollowUpAndKeepingLogsClean() throws Exception {
        String submitKey = "submit-" + UUID.randomUUID();
        CommandResult submitted = inContext(ownerA, () -> commands.submit(submit(BODY, submitKey)));
        assertThat(submitted.created()).isTrue();
        OutboundMessage message = submitted.message();
        assertThat(message.status()).isEqualTo(MessageStatus.PENDING_APPROVAL);
        assertThat(message.version()).isEqualTo(1);
        assertThat(message.sourcePolicyVersion()).isEqualTo(policyVersion);

        CommandResult replay = inContext(ownerA, () -> commands.submit(submit(BODY, submitKey)));
        assertThat(replay.created()).isFalse();
        assertThat(replay.message().id()).isEqualTo(message.id());
        assertThatRefused(() -> inContext(ownerA, () -> commands.submit(submit(BODY + " ¡Saludos!", submitKey))),
                MessagingErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThatRefused(() -> inContext(ownerA, () -> commands.submit(submit(BODY, "other-" + UUID.randomUUID()))),
                MessagingErrorCode.MESSAGE_ALREADY_OPEN);

        CommandResult approved = inContext(ownerA, () -> commands.approve(
                new ApprovalCommand(message.id(), NOTE, "1", "approve-" + UUID.randomUUID())));
        assertThat(approved.message().status()).isEqualTo(MessageStatus.APPROVED);
        assertThat(approved.record()).isPresent();
        assertThat(approved.message().version()).isEqualTo(2);

        String providerMessageId = "wamid." + UUID.randomUUID();
        OutboundMessage sent = inContext(ownerA, () -> driver.sendAccepted(ownerA, message.id(), providerMessageId));
        assertThat(sent.status()).isEqualTo(MessageStatus.SENT);
        assertThat(sent.attemptCount()).isEqualTo(1);
        assertThat(sent.providerMessageId()).isEqualTo(providerMessageId);
        assertThat(sent.version()).isEqualTo(5);

        assertThatRefused(() -> inContext(ownerA, () -> commands.cancel(
                new CancellationCommand(message.id(), NOTE, "5", "cancel-" + UUID.randomUUID()))),
                MessagingErrorCode.INVALID_TRANSITION);

        Instant deliveredAt = clock.instant();
        DeliveryStatusResult delivered = asProvider(() -> sink.apply(new DeliveryStatusReport(providerMessageId,
                DeliveryStatus.DELIVERED, deliveredAt, Optional.empty())));
        assertThat(delivered).isEqualTo(new DeliveryStatusResult.Applied(MessageStatus.SENT, MessageStatus.DELIVERED));
        DeliveryStatusResult late = asProvider(() -> sink.apply(new DeliveryStatusReport(providerMessageId,
                DeliveryStatus.SENT, deliveredAt, Optional.empty())));
        assertThat(late).isInstanceOf(DeliveryStatusResult.Ignored.class);
        assertThat(asProvider(() -> sink.apply(new DeliveryStatusReport("wamid.unknown-" + UUID.randomUUID(),
                DeliveryStatus.READ, deliveredAt, Optional.empty())))).isInstanceOf(DeliveryStatusResult.NotFound.class);

        OutboundMessage current = inContext(ownerA, () -> messages.findById(tenantA.id().value(), message.id())).orElseThrow();
        assertThat(current.status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(current.deliveredAt()).isNotNull();
        assertThat(current.version()).isEqualTo(7);

        List<MessageEvent> events = inContext(ownerA, () -> messages.findEvents(tenantA.id().value(), message.id()));
        assertThat(events).extracting(MessageEvent::sequenceNumber).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(events.getLast().sequenceNumber()).isEqualTo(current.version());
        assertThat(events).extracting(MessageEvent::type).containsExactly(MessageEventType.SUBMITTED,
                MessageEventType.APPROVED, MessageEventType.SEND_REQUESTED, MessageEventType.SEND_ATTEMPT_STARTED,
                MessageEventType.SENT, MessageEventType.DELIVERY_UPDATED, MessageEventType.DELIVERY_UPDATED);
        assertThat(events).extracting(MessageEvent::applied).containsExactly(true, true, true, true, true, true, false);
        assertThat(inContext(ownerA, () -> messages.findAttempts(tenantA.id().value(), message.id())))
                .hasSize(1).first().satisfies(attempt -> assertThat(attempt.providerMessageId()).isEqualTo(providerMessageId));

        var evaluation = inContext(ownerA, () -> followUps.evaluate(customer.id()));
        assertThat(evaluation.status()).isEqualTo(FollowUpStatus.NOT_YET_DUE);
        assertThat(evaluation.timingSource()).isEqualTo(FollowUpTimingSource.LAST_OUTBOUND_MESSAGE);
        assertThat(inContext(ownerA, () -> followUps.dueQueue(new FollowUpQueueQuery(null, null, 10))).items())
                .extracting(item -> item.customerId()).doesNotContain(customer.id());
        assertThat(inContext(ownerA, () -> followUps.customerPolicy(customer.id())).explicitNextDate()).isNull();

        List<AuditEvent> audit = inContext(ownerA, () -> auditReader.read(null, 100).events());
        assertThat(audit).extracting(AuditEvent::type).contains(AuditEventType.MESSAGE_SUBMITTED,
                AuditEventType.MESSAGE_APPROVED, AuditEventType.MESSAGE_SEND_REQUESTED, AuditEventType.MESSAGE_SENT,
                AuditEventType.MESSAGE_DELIVERY_UPDATED);
        assertThat(audit).filteredOn(event -> event.type() == AuditEventType.MESSAGE_DELIVERY_UPDATED).hasSize(1)
                .allSatisfy(event -> {
                    assertThat(event.actorId()).isNull();
                    assertThat(event.membershipId()).isNull();
                    assertThat(event.metadata()).isInstanceOf(AuditMetadata.MessageTransition.class);
                });
        // setUp configured the customer policy once; the cadence touch must not add a second policy audit row.
        assertThat(audit).filteredOn(event -> event.type() == AuditEventType.CUSTOMER_FOLLOW_UP_POLICY_CHANGED).hasSize(1);

        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent entry : logs.list) {
            String line = entry.getFormattedMessage();
            assertThat(line).doesNotContain(BODY).doesNotContain(NOTE).doesNotContain(providerMessageId)
                    .doesNotContain(message.recipientPhone());
        }
    }

    @Test
    void rejectsAndCancelsThroughTheirOwnRecordsAndRefusesStaleOrForeignCommands() throws Exception {
        OutboundMessage first = inContext(ownerA, () -> commands.submit(submit(BODY, "s-" + UUID.randomUUID()))).message();
        String rejectKey = "reject-" + UUID.randomUUID();
        CommandResult rejected = inContext(ownerA, () -> commands.reject(new ApprovalCommand(first.id(), NOTE, "1", rejectKey)));
        assertThat(rejected.message().status()).isEqualTo(MessageStatus.REJECTED);
        assertThat(inContext(ownerA, () -> commands.reject(new ApprovalCommand(first.id(), NOTE, "1", rejectKey))).created())
                .isFalse();
        assertThatRefused(() -> inContext(ownerA, () -> commands.approve(
                new ApprovalCommand(first.id(), null, "2", "a-" + UUID.randomUUID()))), MessagingErrorCode.INVALID_TRANSITION);

        OutboundMessage second = inContext(ownerA, () -> commands.submit(submit(BODY, "s-" + UUID.randomUUID()))).message();
        assertThatRefused(() -> inContext(ownerA, () -> commands.cancel(
                new CancellationCommand(second.id(), null, "9", "c-" + UUID.randomUUID()))), MessagingErrorCode.STALE_VERSION);
        assertThatRefused(() -> inContext(ownerB, () -> commands.cancel(
                new CancellationCommand(second.id(), null, "1", "c-" + UUID.randomUUID()))), MessagingErrorCode.NOT_FOUND);
        assertThatRefused(() -> inContext(viewerA, () -> commands.cancel(
                new CancellationCommand(second.id(), null, "1", "c-" + UUID.randomUUID()))), MessagingErrorCode.FORBIDDEN);
        CommandResult cancelled = inContext(ownerA, () -> commands.cancel(
                new CancellationCommand(second.id(), NOTE, "1", "c-" + UUID.randomUUID())));
        assertThat(cancelled.message().status()).isEqualTo(MessageStatus.CANCELLED);
        assertThat(cancelled.record()).isPresent();

        List<MessageEvent> events = inContext(ownerA, () -> messages.findEvents(tenantA.id().value(), second.id()));
        assertThat(events).extracting(MessageEvent::sequenceNumber).containsExactly(1L, 2L);
        assertThat(rowCount("outbound_message_cancellations")).isEqualTo(1);
    }

    @Test
    void refusesSubmitWhenForbiddenStaleUnmappedOrCrossTenantWithoutLeavingRows() throws Exception {
        assertThatRefused(() -> inContext(viewerA, () -> commands.submit(submit(BODY, "v-" + UUID.randomUUID()))),
                MessagingErrorCode.FORBIDDEN);
        assertThat(inContext(ownerA, () -> auditReader.read(null, 100).events()))
                .extracting(AuditEvent::type).contains(AuditEventType.AUTHORIZATION_DENIED);

        assertThatRefused(() -> inContext(ownerA, () -> commands.submit(new SubmitMessageCommand(customer.id().value(),
                contactId, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", BODY, "es-419", "MANUAL",
                Long.toString(policyVersion + 5), "st-" + UUID.randomUUID()))), MessagingErrorCode.STALE_VERSION);
        assertThatRefused(() -> inContext(ownerA, () -> commands.submit(new SubmitMessageCommand(customer.id().value(),
                contactId, "REPEAT_PURCHASE_FOLLOW_UP", "REPEAT_PURCHASE", BODY, "es-419", "MANUAL",
                Long.toString(policyVersion), "tm-" + UUID.randomUUID()))), MessagingErrorCode.TEMPLATE_NOT_MAPPED);
        assertThatRefused(() -> inContext(ownerA, () -> commands.submit(new SubmitMessageCommand(customer.id().value(),
                contactId, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", BODY + " Visite https://ejemplo.com",
                "es-419", "MANUAL", Long.toString(policyVersion), "b-" + UUID.randomUUID()))),
                MessagingErrorCode.BODY_REJECTED);
        assertThatRefused(() -> inContext(ownerB, () -> commands.submit(submit(BODY, "x-" + UUID.randomUUID()))),
                MessagingErrorCode.NOT_FOUND);

        assertThat(messageCount()).isZero();
        assertThat(rowCount("outbound_message_idempotency_keys")).isZero();
    }

    @Test
    void auditFailureRollsBackTheMessageItsEventAndItsIdempotencyKey() throws Exception {
        doThrow(new AuditPersistenceException()).when(auditRecorder).messageTransition(any(), any(), any());

        assertThatThrownBy(() -> inContext(ownerA, () -> commands.submit(submit(BODY, "af-" + UUID.randomUUID()))))
                .isInstanceOf(AuditPersistenceException.class);

        assertThat(messageCount()).isZero();
        assertThat(rowCount("outbound_message_events")).isZero();
        assertThat(rowCount("outbound_message_idempotency_keys")).isZero();
    }

    @Test
    void concurrentSubmitsForOneCustomerLeaveExactlyOneOpenMessage() throws Exception {
        int writers = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<MessagingErrorCode>> outcomes = new java.util.ArrayList<>();
            for (int i = 0; i < writers; i++) {
                outcomes.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        inContext(ownerA, () -> commands.submit(submit(BODY, "race-" + UUID.randomUUID())));
                        return null;
                    } catch (MessagingRefusedException refused) {
                        return refused.code();
                    }
                }));
            }
            start.countDown();
            List<MessagingErrorCode> codes = new java.util.ArrayList<>();
            for (Future<MessagingErrorCode> outcome : outcomes) {
                codes.add(outcome.get(30, TimeUnit.SECONDS));
            }
            assertThat(codes).filteredOn(code -> code == null).hasSize(1);
            assertThat(codes).filteredOn(code -> code != null).hasSize(writers - 1)
                    .allMatch(code -> code == MessagingErrorCode.MESSAGE_ALREADY_OPEN);
        } finally {
            pool.shutdownNow();
        }
        assertThat(messageCount()).isEqualTo(1);
    }

    @Test
    void deliverySinkRequiresTheProviderContextAndRefusesAMemberContext() throws Exception {
        DeliveryStatusReport report = new DeliveryStatusReport("wamid.none", DeliveryStatus.DELIVERED, clock.instant(),
                Optional.empty());
        assertThatRefused(() -> auditExecution.callWithCorrelation(UUID.randomUUID(), () -> sink.apply(report)),
                MessagingErrorCode.FORBIDDEN);
        assertThatRefused(() -> inContext(ownerA, () -> providerContexts.callWithContext(
                new ProviderContext(tenantA.id()), () -> sink.apply(report))), MessagingErrorCode.FORBIDDEN);
    }

    @Test
    void phoneCorrectionAfterSubmitSucceedsAndStrandsThePendingMessage() throws Exception {
        // AC-13: no foreign key to the contact, so the customer edit still deletes the old contact row.
        OutboundMessage message = inContext(ownerA, () -> commands.submit(submit(BODY, "pc-" + UUID.randomUUID()))).message();
        assertThat(message.contactId()).isEqualTo(contactId);

        Customer current = inContext(ownerA, () -> customers.get(customer.id()));
        // Same valid CR mobile prefix as setUp, a different suffix, so the edit is a real number change.
        String originalSuffix = current.phones().getFirst().e164().substring(current.phones().getFirst().e164().length() - 4);
        String replacement = "8888" + String.format("%04d", (Integer.parseInt(originalSuffix) + 1) % 10000);
        Customer edited = inContext(ownerA, () -> customers.update(customer.id(), current.version(),
                current.displayName(), current.notes(), List.of(new PhoneInput(replacement, "CR", true))));
        assertThat(edited.phones()).hasSize(1);
        assertThat(edited.phones().getFirst().id()).isNotEqualTo(contactId);
        assertThat(inContext(ownerA, () -> jdbc.queryForObject(
                "SELECT count(*) FROM dokene.customer_phone_contacts WHERE tenant_id = ? AND id = ?",
                Integer.class, tenantA.id().value(), contactId))).isZero();

        OutboundMessage stored = inContext(ownerA, () -> messages.findById(tenantA.id().value(), message.id())).orElseThrow();
        assertThat(stored.contactId()).isEqualTo(contactId);
        assertThat(stored.recipientPhone()).isEqualTo(message.recipientPhone());
        assertThat(stored.status()).isEqualTo(MessageStatus.PENDING_APPROVAL);

        assertThatRefused(() -> inContext(ownerA, () -> commands.approve(
                new ApprovalCommand(message.id(), NOTE, "1", "pa-" + UUID.randomUUID()))),
                MessagingErrorCode.CONTACT_NOT_OWNED);
        assertThat(inContext(ownerA, () -> messages.findEvents(tenantA.id().value(), message.id()))).hasSize(1);
        assertThat(inContext(ownerA, () -> auditReader.read(null, 100).events()))
                .extracting(AuditEvent::type).doesNotContain(AuditEventType.MESSAGE_APPROVED);
        assertThat(rowCount("outbound_message_approvals")).isZero();
    }

    @Test
    void deliveryReportTimesAreBoundedAndTheCadenceAnchorNeverMovesBackwards() throws Exception {
        // AC-14. The tenant zone is UTC here, so the asserted day is the day of the bounded instant itself.
        OutboundMessage message = inContext(ownerA, () -> commands.submit(submit(BODY, "rt-" + UUID.randomUUID()))).message();
        inContext(ownerA, () -> commands.approve(new ApprovalCommand(message.id(), NOTE, "1", "ra-" + UUID.randomUUID())));
        String providerMessageId = "wamid." + UUID.randomUUID();
        inContext(ownerA, () -> driver.sendUnknownOutcome(ownerA, message.id(), providerMessageId));

        Instant before = clock.instant();
        Instant farFuture = before.plusSeconds(7 * 86400);
        DeliveryStatusResult sent = asProvider(() -> sink.apply(new DeliveryStatusReport(providerMessageId,
                DeliveryStatus.SENT, farFuture, Optional.empty())));
        Instant after = clock.instant();
        assertThat(sent).isEqualTo(new DeliveryStatusResult.Applied(MessageStatus.SENDING, MessageStatus.SENT));
        OutboundMessage sentMessage = inContext(ownerA, () -> messages.findById(tenantA.id().value(), message.id())).orElseThrow();
        assertThat(sentMessage.sentAt()).isBetween(before, after).isBefore(farFuture);
        LocalDate anchorDay = sentMessage.sentAt().atZone(ZoneId.of("UTC")).toLocalDate();
        assertThat(inContext(ownerA, () -> followUps.customerPolicy(customer.id())).lastOutboundMessageDate())
                .isEqualTo(anchorDay);

        Instant beforeCreation = message.createdAt().minusSeconds(30 * 86400);
        DeliveryStatusResult delivered = asProvider(() -> sink.apply(new DeliveryStatusReport(providerMessageId,
                DeliveryStatus.DELIVERED, beforeCreation, Optional.empty())));
        assertThat(delivered).isEqualTo(new DeliveryStatusResult.Applied(MessageStatus.SENT, MessageStatus.DELIVERED));
        OutboundMessage deliveredMessage = inContext(ownerA, () -> messages.findById(tenantA.id().value(), message.id())).orElseThrow();
        assertThat(deliveredMessage.deliveredAt()).isEqualTo(message.createdAt());
        assertThat(deliveredMessage.sentAt()).isEqualTo(sentMessage.sentAt());

        // The anchor itself refuses to move backwards even when a caller hands it an older day.
        inContext(ownerA, () -> new TransactionTemplate(transactionManager).execute(status -> {
            touches.recordOutboundMessage(customer.id(), beforeCreation);
            return null;
        }));
        assertThat(inContext(ownerA, () -> followUps.customerPolicy(customer.id())).lastOutboundMessageDate())
                .isEqualTo(anchorDay);
    }

    @Test
    void outboundMessagesCarryNoForeignKeyToTheContact() {
        // AC-13: the schema itself must let the customer module delete a corrected phone row.
        List<String> foreignKeys = jdbc.queryForList("""
                SELECT conname FROM pg_constraint
                WHERE conrelid = 'dokene.outbound_messages'::regclass AND contype = 'f'
                ORDER BY conname
                """, String.class);
        assertThat(foreignKeys).containsExactly("fk_outbound_messages_customer", "fk_outbound_messages_tenant");
    }

    @Test
    void strandedMessageCanBeRejectedAndTheCustomerMessagedAgainOnTheCorrectedPhone() throws Exception {
        // AC-13 recovery: reject skips the contact guard, which frees the one open slot for a fresh submit.
        OutboundMessage stranded = inContext(ownerA, () -> commands.submit(submit(BODY, "st-" + UUID.randomUUID()))).message();
        Customer current = inContext(ownerA, () -> customers.get(customer.id()));
        String originalSuffix = current.phones().getFirst().e164().substring(current.phones().getFirst().e164().length() - 4);
        String replacement = "8888" + String.format("%04d", (Integer.parseInt(originalSuffix) + 2) % 10000);
        Customer edited = inContext(ownerA, () -> customers.update(customer.id(), current.version(),
                current.displayName(), current.notes(), List.of(new PhoneInput(replacement, "CR", true))));
        UUID replacementContactId = edited.phones().getFirst().id();
        var policy = inContext(ownerA, () -> contacts.get(customer.id()));
        inContext(ownerA, () -> contacts.changeConsent(customer.id(), replacementContactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));

        long currentPolicyVersion = inContext(ownerA, () -> followUps.evaluateSnapshot(customer.id())).policyVersion();
        assertThatRefused(() -> inContext(ownerA, () -> commands.submit(new SubmitMessageCommand(customer.id().value(),
                replacementContactId, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", BODY, "es-419", "MANUAL",
                Long.toString(currentPolicyVersion), "blocked-" + UUID.randomUUID()))), MessagingErrorCode.MESSAGE_ALREADY_OPEN);

        CommandResult rejected = inContext(ownerA, () -> commands.reject(
                new ApprovalCommand(stranded.id(), NOTE, "1", "sr-" + UUID.randomUUID())));
        assertThat(rejected.message().status()).isEqualTo(MessageStatus.REJECTED);
        assertThat(rejected.message().contactId()).isEqualTo(contactId);

        CommandResult resubmitted = inContext(ownerA, () -> commands.submit(new SubmitMessageCommand(
                customer.id().value(), replacementContactId, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", BODY, "es-419",
                "MANUAL", Long.toString(currentPolicyVersion), "again-" + UUID.randomUUID())));
        assertThat(resubmitted.created()).isTrue();
        assertThat(resubmitted.message().contactId()).isEqualTo(replacementContactId);
        assertThat(resubmitted.message().recipientPhone()).isEqualTo(edited.phones().getFirst().e164());
        assertThat(resubmitted.message().status()).isEqualTo(MessageStatus.PENDING_APPROVAL);
    }

    @Test
    void cadenceAnchorStillAdvancesWhenANewerInstantArrives() throws Exception {
        // AC-14: the anchor is monotonic, not frozen; a later send must move it forward.
        LocalDate baseline = inContext(ownerA, () -> followUps.customerPolicy(customer.id())).lastOutboundMessageDate();
        assertThat(baseline).isNull();
        Instant first = clock.instant();
        Instant later = first.plusSeconds(3 * 86400);
        inContext(ownerA, () -> new TransactionTemplate(transactionManager).execute(status -> {
            touches.recordOutboundMessage(customer.id(), first);
            return null;
        }));
        assertThat(inContext(ownerA, () -> followUps.customerPolicy(customer.id())).lastOutboundMessageDate())
                .isEqualTo(first.atZone(ZoneId.of("UTC")).toLocalDate());
        inContext(ownerA, () -> new TransactionTemplate(transactionManager).execute(status -> {
            touches.recordOutboundMessage(customer.id(), later);
            return null;
        }));
        assertThat(inContext(ownerA, () -> followUps.customerPolicy(customer.id())).lastOutboundMessageDate())
                .isEqualTo(later.atZone(ZoneId.of("UTC")).toLocalDate());
    }

    private SubmitMessageCommand submit(String body, String idempotencyKey) {
        return new SubmitMessageCommand(customer.id().value(), contactId, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP",
                body, "es-419", "MANUAL", Long.toString(policyVersion), idempotencyKey);
    }

    /** Counts run under the tenant context because RLS hides every row from a context free statement. */
    private int messageCount() throws Exception {
        return rowCount("outbound_messages");
    }

    private int rowCount(String table) throws Exception {
        return inContext(ownerA, () -> jdbc.queryForObject(
                "SELECT count(*) FROM dokene." + table + " WHERE tenant_id = ?", Integer.class, tenantA.id().value()));
    }

    private <T> T asProvider(Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> providerContexts.callWithContext(new ProviderContext(tenantA.id()), operation::call));
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
