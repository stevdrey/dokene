// ABOUTME: Manual dispatch against PostgreSQL (issue 138): idempotent claim, last-moment policy gate, concurrency,
// ABOUTME: uncertain outcomes and audit outages. Every refusal must leave zero provider calls and no attempt rows.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
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
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.messaging.application.ApprovalCommand;
import io.github.stevdrey.dokene.messaging.application.CommandResult;
import io.github.stevdrey.dokene.messaging.application.OutboundDispatchService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageCommandService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.messaging.application.OutboundSendCommand;
import io.github.stevdrey.dokene.messaging.application.SendMessageCommand;
import io.github.stevdrey.dokene.messaging.application.SubmitMessageCommand;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.messaging.domain.SendAttemptOutcome;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubSafetyControl;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubTemplateResolver;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// A short dispatch deadline so the deadline test is fast; every scripted call answers well within it. The Graph
// timeout must stay below it (DispatchDeadlineCheck), although no test here reaches Graph.
@SpringBootTest(properties = {"dokene.ai.provider=fake", "dokene.messaging.send-timeout=2s",
        "dokene.integration.whatsapp.graph.timeout=1s"})
@Import({MappedGeneralFollowUpGateConfiguration.class, ScriptedMessagingProvider.Configuration.class})
class OutboundDispatchIntegrationTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired OutboundDispatchService dispatch;
    @Autowired OutboundMessageCommandService commands;
    @Autowired OutboundMessageRepository messages;
    @Autowired ScriptedMessagingProvider provider;
    @Autowired StubTemplateResolver templates;
    @Autowired StubSafetyControl safety;
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
    @MockitoSpyBean AuditRecorder auditRecorder;

    private TenantContext ownerA;
    private TenantContext viewerA;
    private TenantContext ownerB;
    private Customer customer;
    private UUID contactId;

    @BeforeEach
    void setUp() throws Exception {
        MappedGeneralFollowUpGateConfiguration.reset(templates, safety);
        safety.blockedWith = null;
        provider.reset();
        Instant now = Instant.now();
        Tenant tenantA = seedTenant(tenants, contexts, "Dispatch A " + UUID.randomUUID(), now);
        Tenant tenantB = seedTenant(tenants, contexts, "Dispatch B " + UUID.randomUUID(), now);
        ownerA = context(seedMembership(memberships, contexts, tenantA.id(), new IdentityId(UUID.randomUUID()),
                TenantRole.OWNER, now));
        viewerA = context(seedMembership(memberships, contexts, tenantA.id(), new IdentityId(UUID.randomUUID()),
                TenantRole.VIEWER, now));
        ownerB = context(seedMembership(memberships, contexts, tenantB.id(), new IdentityId(UUID.randomUUID()),
                TenantRole.OWNER, now));
        inContext(ownerA, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        customer = eligibleCustomer("Dispatch customer");
        contactId = customer.phones().getFirst().id();
    }

    @Test
    void sendsTheApprovedBindingOnceAndRecordsAttemptEventsAuditAndCadence() throws Exception {
        OutboundMessage approved = approvedMessage();

        CommandResult sent = inContext(ownerA, () -> dispatch.send(send(approved, "send-" + UUID.randomUUID())));

        assertThat(sent.created()).isTrue();
        OutboundMessage message = sent.message();
        assertThat(message.status()).isEqualTo(MessageStatus.SENT);
        assertThat(message.providerMessageId()).startsWith("wamid.SYNTHETIC-");
        assertThat(message.attemptCount()).isEqualTo(1);
        assertThat(sent.detail().attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.outcome()).isEqualTo(SendAttemptOutcome.ACCEPTED);
            assertThat(attempt.requestedByMembershipId()).isEqualTo(ownerA.membershipId().value());
        });
        assertThat(sent.record()).containsInstanceOf(SendAttempt.class);
        OutboundSendCommand call = provider.calls().getFirst();
        assertThat(provider.calls()).hasSize(1);
        assertThat(call.parameters()).containsExactly(customer.displayName(), BODY);
        assertThat(call.sendKey()).isEqualTo(approved.sendKey());
        assertThat(call.recipientPhone()).isEqualTo(approved.recipientPhone());
        assertThat(eventTypes(approved)).containsExactly(MessageEventType.SUBMITTED, MessageEventType.APPROVED,
                MessageEventType.SEND_REQUESTED, MessageEventType.SEND_ATTEMPT_STARTED, MessageEventType.SENT);
        assertThat(auditTypes()).contains(AuditEventType.MESSAGE_SEND_REQUESTED, AuditEventType.MESSAGE_SENT);
        // The send anchors the cadence (ADR 0023 §4.6), so the customer is no longer due.
        assertThat(inContext(ownerA, () -> followUps.evaluateSnapshot(customer.id())).evaluation().status())
                .isNotIn(FollowUpStatus.DUE, FollowUpStatus.OVERDUE);
    }

    @Test
    void aDuplicateClickReplaysTheSameKeyWithoutASecondProviderCall() throws Exception {
        OutboundMessage approved = approvedMessage();
        SendMessageCommand command = send(approved, "double-" + UUID.randomUUID());

        CommandResult first = inContext(ownerA, () -> dispatch.send(command));
        CommandResult replay = inContext(ownerA, () -> dispatch.send(command));

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.message().version()).isEqualTo(first.message().version());
        assertThat(replay.record()).map(record -> ((SendAttempt) record).id())
                .isEqualTo(first.record().map(record -> ((SendAttempt) record).id()));
        assertThat(provider.calls()).hasSize(1);
    }

    @Test
    void reusingAKeyForAnotherVersionOrMessageIsAConflict() throws Exception {
        OutboundMessage approved = approvedMessage();
        String key = "reuse-" + UUID.randomUUID();
        inContext(ownerA, () -> dispatch.send(send(approved, key)));

        assertThatRefused(() -> inContext(ownerA, () -> dispatch.send(
                new SendMessageCommand(approved.id(), Long.toString(approved.version() + 1), key))),
                MessagingErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThatRefused(() -> inContext(ownerA, () -> dispatch.send(
                new SendMessageCommand(UUID.randomUUID(), Long.toString(approved.version()), key))),
                MessagingErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(provider.calls()).hasSize(1);
    }

    @Test
    void concurrentClicksWithDifferentKeysReachTheProviderOnce() throws Exception {
        OutboundMessage approved = approvedMessage();
        List<Object> outcomes = race(2,
                index -> dispatch.send(send(approved, "race-" + index + "-" + UUID.randomUUID())));

        assertThat(outcomes).filteredOn(CommandResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(MessagingErrorCode.class::isInstance)
                .allSatisfy(code -> assertThat(code).isIn(MessagingErrorCode.STALE_VERSION,
                        MessagingErrorCode.INVALID_TRANSITION));
        assertThat(provider.calls()).hasSize(1);
        assertThat(attempts(approved)).hasSize(1);
    }

    @Test
    void concurrentClicksWithTheSameKeyCreateOneAttemptAndReplayTheOther() throws Exception {
        OutboundMessage approved = approvedMessage();
        SendMessageCommand command = send(approved, "same-" + UUID.randomUUID());
        List<Object> outcomes = race(2, index -> dispatch.send(command));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(CommandResult.class));
        assertThat(outcomes).map(outcome -> ((CommandResult) outcome).created()).containsExactlyInAnyOrder(true, false);
        assertThat(provider.calls()).hasSize(1);
        assertThat(attempts(approved)).hasSize(1);
    }

    @Test
    void deterministicDenialsMakeNoProviderCallAndWriteNothing() throws Exception {
        OutboundMessage approved = approvedMessage();

        safety.blockedWith = MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY;
        assertDeniedWithoutEffects(approved, MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY);
        safety.blockedWith = MessagingErrorCode.OUTBOUND_DISABLED_FOR_TENANT;
        assertDeniedWithoutEffects(approved, MessagingErrorCode.OUTBOUND_DISABLED_FOR_TENANT);
        safety.blockedWith = MessagingErrorCode.INTEGRATION_DISABLED;
        assertDeniedWithoutEffects(approved, MessagingErrorCode.INTEGRATION_DISABLED);
        safety.blockedWith = null;

        // A credential rotation after approval changes the sender the approval bound.
        safety.credentialRevision = 2;
        assertDeniedWithoutEffects(approved, MessagingErrorCode.APPROVAL_INVALIDATED);
        safety.credentialRevision = 1;

        // A later mapping edit must not ride on the earlier approval; an unmapped intent is refused outright.
        templates.version = 2;
        assertDeniedWithoutEffects(approved, MessagingErrorCode.APPROVAL_INVALIDATED);
        templates.version = 1;
        templates.mapped = false;
        assertDeniedWithoutEffects(approved, MessagingErrorCode.TEMPLATE_NOT_MAPPED);
        templates.mapped = true;

        changeConsent(ConsentStatus.REVOKED);
        assertDeniedWithoutEffects(approved, MessagingErrorCode.NO_CONTACT_CONSENT);
        changeConsent(ConsentStatus.GRANTED);
        // Re-granting starts a new consent run, so the old approval no longer covers it.
        assertDeniedWithoutEffects(approved, MessagingErrorCode.APPROVAL_INVALIDATED);
    }

    @Test
    void doNotContactAndArchiveBlockTheSend() throws Exception {
        OutboundMessage approved = approvedMessage();
        inContext(ownerA, () -> contacts.changeDoNotContact(customer.id(), true, ContactIntentSource.CUSTOMER_VERBAL,
                contacts.get(customer.id()).version()));
        assertDeniedWithoutEffects(approved, MessagingErrorCode.DO_NOT_CONTACT);
        inContext(ownerA, () -> contacts.changeDoNotContact(customer.id(), false, ContactIntentSource.CUSTOMER_VERBAL,
                contacts.get(customer.id()).version()));
        inContext(ownerA, () -> {
            customers.archive(customer.id(), customers.get(customer.id()).version());
            return null;
        });
        assertDeniedWithoutEffects(approved, MessagingErrorCode.CUSTOMER_ARCHIVED);
    }

    @Test
    void staleVersionMissingPermissionAndOtherTenantsAreRefusedBeforeAnyEffect() throws Exception {
        OutboundMessage approved = approvedMessage();

        assertThatRefused(() -> inContext(ownerA, () -> dispatch.send(new SendMessageCommand(approved.id(),
                Long.toString(approved.version() - 1), "stale-" + UUID.randomUUID()))),
                MessagingErrorCode.STALE_VERSION);
        assertThatRefused(() -> inContext(viewerA, () -> dispatch.send(send(approved, "viewer-" + UUID.randomUUID()))),
                MessagingErrorCode.FORBIDDEN);
        assertThatRefused(() -> inContext(ownerB, () -> dispatch.send(send(approved, "other-" + UUID.randomUUID()))),
                MessagingErrorCode.NOT_FOUND);
        assertThatRefused(() -> inContext(ownerA, () -> dispatch.send(
                new SendMessageCommand(approved.id(), Long.toString(approved.version()), null))),
                MessagingErrorCode.INVALID_INPUT);
        assertThatRefused(() -> inContext(ownerA, () -> dispatch.send(
                new SendMessageCommand(approved.id(), null, "no-version-" + UUID.randomUUID()))),
                MessagingErrorCode.INVALID_INPUT);

        assertThat(provider.calls()).isEmpty();
        assertThat(current(approved).status()).isEqualTo(MessageStatus.APPROVED);
        assertThat(attempts(approved)).isEmpty();
    }

    @Test
    void aTransientFailureReturnsToApprovedAndTheAttemptCapEndsInFailed() throws Exception {
        OutboundMessage message = approvedMessage();
        provider.next = () -> new ProviderSendResult.FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE);

        for (int attempt = 1; attempt <= 3; attempt++) {
            OutboundMessage before = message;
            message = inContext(ownerA, () -> dispatch.send(send(before, "retry-" + UUID.randomUUID()))).message();
            assertThat(message.attemptCount()).isEqualTo(attempt);
            assertThat(message.status()).isEqualTo(attempt < 3 ? MessageStatus.APPROVED : MessageStatus.FAILED);
        }
        assertThat(message.failureCategory()).isEqualTo(FailureCategory.PROVIDER_UNAVAILABLE);
        assertThat(provider.calls()).hasSize(3);
        assertThat(auditTypes()).contains(AuditEventType.MESSAGE_SEND_FAILED);
    }

    @Test
    void anUnknownOutcomeIsKeptAndNeverResentAutomaticallyOrManually() throws Exception {
        OutboundMessage approved = approvedMessage();
        provider.next = () -> new ProviderSendResult.OutcomeUnknown(Optional.empty());

        OutboundMessage unknown = inContext(ownerA, () -> dispatch.send(send(approved, "unknown-" + UUID.randomUUID())))
                .message();

        assertThat(unknown.status()).isEqualTo(MessageStatus.SENDING);
        assertThat(unknown.outcomeUnknown()).isTrue();
        assertThat(auditTypes()).contains(AuditEventType.MESSAGE_SEND_OUTCOME_UNKNOWN);
        assertThatRefused(() -> inContext(ownerA, () -> dispatch.send(send(unknown, "again-" + UUID.randomUUID()))),
                MessagingErrorCode.INVALID_TRANSITION);
        assertThat(provider.calls()).hasSize(1);
    }

    @Test
    void aProviderThatThrowsIsRecordedAsAnUnknownOutcome() throws Exception {
        OutboundMessage approved = approvedMessage();
        provider.duringSend = () -> {
            throw new IllegalStateException("connection reset after write");
        };

        OutboundMessage unknown = inContext(ownerA, () -> dispatch.send(send(approved, "throw-" + UUID.randomUUID())))
                .message();

        assertThat(unknown.status()).isEqualTo(MessageStatus.SENDING);
        assertThat(unknown.outcomeUnknown()).isTrue();
        assertThat(attempts(approved)).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo(SendAttemptOutcome.OUTCOME_UNKNOWN));
    }

    @Test
    void anAuditOutageBeforeTheClaimRollsEverythingBackWithoutAProviderCall() throws Exception {
        OutboundMessage approved = approvedMessage();
        doThrow(new AuditPersistenceException()).when(auditRecorder)
                .messageTransition(any(), eq(AuditEventType.MESSAGE_SEND_REQUESTED), any());

        assertThatThrownBy(() -> inContext(ownerA, () -> dispatch.send(send(approved, "audit-" + UUID.randomUUID()))))
                .isInstanceOf(AuditPersistenceException.class);

        assertThat(provider.calls()).isEmpty();
        assertThat(current(approved).status()).isEqualTo(MessageStatus.APPROVED);
        assertThat(current(approved).version()).isEqualTo(approved.version());
        assertThat(attempts(approved)).isEmpty();
        assertThat(idempotencyRows(approved)).isZero();
    }

    @Test
    void anAuditOutageAfterTheCallKeepsTheClaimAndHaltsFurtherSends() throws Exception {
        OutboundMessage approved = approvedMessage();
        doThrow(new AuditPersistenceException()).when(auditRecorder)
                .messageTransition(any(), eq(AuditEventType.MESSAGE_SENT), any());

        assertThatThrownBy(() -> inContext(ownerA, () -> dispatch.send(send(approved, "late-" + UUID.randomUUID()))))
                .isInstanceOf(AuditPersistenceException.class);

        // The provider was called once; the committed claim survives and no outcome is pretended.
        OutboundMessage halted = current(approved);
        assertThat(provider.calls()).hasSize(1);
        assertThat(halted.status()).isEqualTo(MessageStatus.SENDING);
        assertThat(halted.outcomeUnknown()).isFalse();
        assertThat(attempts(approved)).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo(SendAttemptOutcome.STARTED));
        assertThatRefused(() -> inContext(ownerA, () -> dispatch.send(send(halted, "after-" + UUID.randomUUID()))),
                MessagingErrorCode.INVALID_TRANSITION);
        // The partial unique index is the durable backstop: no second STARTED attempt can exist for the message.
        assertThatRefused(() -> inContext(ownerA, () -> messages.insertAttempt(new SendAttempt(UUID.randomUUID(),
                halted.tenantId(), halted.id(), 2, halted.sendKey(), SendAttemptOutcome.STARTED, null, null,
                Instant.now(), null, ownerA.membershipId().value(), ownerA.identityId().value()))),
                MessagingErrorCode.INVALID_TRANSITION);
        assertThat(provider.calls()).hasSize(1);
    }

    @Test
    void aRevocationDuringAnInFlightCallCannotRetractItButBlocksTheNextSend() throws Exception {
        OutboundMessage approved = approvedMessage();
        provider.duringSend = () -> {
            try {
                changeConsent(ConsentStatus.REVOKED);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };

        OutboundMessage sent = inContext(ownerA, () -> dispatch.send(send(approved, "inflight-" + UUID.randomUUID())))
                .message();

        assertThat(sent.status()).isEqualTo(MessageStatus.SENT);
        assertThat(provider.calls()).hasSize(1);
        assertThat(inContext(ownerA, () -> contacts.get(customer.id())).consents())
                .anySatisfy(consent -> assertThat(consent.status()).isEqualTo(ConsentStatus.REVOKED));
    }

    private void assertDeniedWithoutEffects(OutboundMessage approved, MessagingErrorCode code) throws Exception {
        assertDeniedWithoutEffects(ownerA, approved, code);
    }

    private void assertDeniedWithoutEffects(TenantContext sender, OutboundMessage approved, MessagingErrorCode code)
            throws Exception {
        OutboundMessage before = current(approved);
        assertThatRefused(() -> inContext(sender, () -> dispatch.send(send(before, "deny-" + UUID.randomUUID()))),
                code);
        OutboundMessage after = current(approved);
        assertThat(after.status()).as(code.name()).isEqualTo(MessageStatus.APPROVED);
        assertThat(after.version()).as(code.name()).isEqualTo(before.version());
        assertThat(attempts(approved)).as(code.name()).isEmpty();
        assertThat(idempotencyRows(approved)).as(code.name()).isZero();
        assertThat(provider.calls()).as(code.name()).isEmpty();
    }

    @Test
    void aMembershipRevokedOrDemotedAfterTheRequestStartedCannotClaimTheSend() throws Exception {
        Instant now = Instant.now();
        TenantMembership revoked = seedMembership(memberships, contexts, ownerA.tenantId(),
                new IdentityId(UUID.randomUUID()), TenantRole.OPERATOR, now);
        TenantMembership demoted = seedMembership(memberships, contexts, ownerA.tenantId(),
                new IdentityId(UUID.randomUUID()), TenantRole.OPERATOR, now);
        // Contexts as a request filter resolved them, before the membership changes below commit.
        TenantContext staleRevoked = context(revoked);
        TenantContext staleDemoted = context(demoted);
        revoked.revoke(Instant.now());
        demoted.changeRole(TenantRole.VIEWER, Instant.now());
        contexts.runWithTenantId(ownerA.tenantId(), () -> {
            memberships.save(revoked);
            memberships.save(demoted);
        });

        OutboundMessage approved = approvedMessage();
        assertDeniedWithoutEffects(staleRevoked, approved, MessagingErrorCode.FORBIDDEN);
        assertDeniedWithoutEffects(staleDemoted, approved, MessagingErrorCode.FORBIDDEN);

        // Each T6 authorization failure is audited on its own, although the claim rolled back (ADR 0023 §4.5).
        List<AuditEvent> denials = inContext(ownerA, () -> auditReader.read(null, 100).events()).stream()
                .filter(event -> event.type() == AuditEventType.AUTHORIZATION_DENIED).toList();
        assertThat(denials).anySatisfy(event -> {
            assertThat(event.membershipId()).isEqualTo(revoked.id());
            assertThat(event.metadata()).isEqualTo(new AuditMetadata.AuthorizationDenied(
                    TenantPermission.MESSAGE_SEND, AuditDenialReason.INACTIVE_MEMBERSHIP));
        });
        assertThat(denials).anySatisfy(event -> {
            assertThat(event.membershipId()).isEqualTo(demoted.id());
            assertThat(event.metadata()).isEqualTo(new AuditMetadata.AuthorizationDenied(
                    TenantPermission.MESSAGE_SEND, AuditDenialReason.INSUFFICIENT_PERMISSION));
        });
    }

    @Test
    void aProviderCallThatMissesTheDispatchDeadlineIsAnUnknownOutcome() throws Exception {
        OutboundMessage approved = approvedMessage();
        provider.duringSend = () -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };

        long started = System.nanoTime();
        OutboundMessage unknown = inContext(ownerA, () -> dispatch.send(send(approved, "slow-" + UUID.randomUUID())))
                .message();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        assertThat(unknown.status()).isEqualTo(MessageStatus.SENDING);
        assertThat(unknown.outcomeUnknown()).isTrue();
        assertThat(provider.calls()).hasSize(1);
        assertThatRefused(() -> inContext(ownerA, () -> dispatch.send(send(unknown, "again-" + UUID.randomUUID()))),
                MessagingErrorCode.INVALID_TRANSITION);
        assertThat(provider.calls()).hasSize(1);
    }

    @Test
    void anInterruptedRequestStillRecordsTheUnknownOutcomeThenKeepsItsInterrupt() throws Exception {
        OutboundMessage approved = approvedMessage();
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        provider.duringSend = () -> {
            inCall.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        AtomicReference<OutboundMessage> recorded = new AtomicReference<>();
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread request = Thread.ofPlatform().start(() -> {
            try {
                recorded.set(inContext(ownerA, () -> dispatch.send(send(approved, "intr-" + UUID.randomUUID())))
                        .message());
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            } finally {
                interruptKept.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertThat(inCall.await(10, TimeUnit.SECONDS)).isTrue();
            request.interrupt();
            request.join(TimeUnit.SECONDS.toMillis(30));
        } finally {
            release.countDown();
        }

        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().status()).isEqualTo(MessageStatus.SENDING);
        assertThat(recorded.get().outcomeUnknown()).isTrue();
        assertThat(attempts(approved)).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo(SendAttemptOutcome.OUTCOME_UNKNOWN));
        assertThat(interruptKept).isTrue();
    }

    @Test
    void anErrorInTheProviderIsAnImmediateUnknownOutcome() throws Exception {
        OutboundMessage approved = approvedMessage();
        provider.duringSend = () -> {
            throw new AssertionError("adapter bug");
        };

        long started = System.nanoTime();
        OutboundMessage unknown = inContext(ownerA, () -> dispatch.send(send(approved, "err-" + UUID.randomUUID())))
                .message();

        // Well inside this class's 2s dispatch deadline: the error completed the call instead of timing it out.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
        assertThat(unknown.status()).isEqualTo(MessageStatus.SENDING);
        assertThat(unknown.outcomeUnknown()).isTrue();
    }

    @Test
    void theProviderRunsUnderTheMembershipAsReReadForTheClaim() throws Exception {
        TenantMembership member = seedMembership(memberships, contexts, ownerA.tenantId(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, Instant.now());
        TenantContext stale = context(member);
        member.changeRole(TenantRole.OPERATOR, Instant.now());
        contexts.runWithTenantId(ownerA.tenantId(), () -> memberships.save(member));
        OutboundMessage approved = approvedMessage();
        AtomicReference<TenantRole> seen = new AtomicReference<>();
        provider.duringSend = () -> seen.set(contexts.requireCurrent().role());

        OutboundMessage sent = inContext(stale, () -> dispatch.send(send(approved, "fresh-" + UUID.randomUUID())))
                .message();

        assertThat(sent.status()).isEqualTo(MessageStatus.SENT);
        assertThat(seen.get()).isEqualTo(TenantRole.OPERATOR);
    }

    private OutboundMessage approvedMessage() throws Exception {
        long policyVersion = inContext(ownerA, () -> followUps.evaluateSnapshot(customer.id())).policyVersion();
        OutboundMessage pending = inContext(ownerA, () -> commands.submit(new SubmitMessageCommand(
                customer.id().value(), contactId, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", BODY, "es-419", "MANUAL",
                Long.toString(policyVersion), "submit-" + UUID.randomUUID()))).message();
        OutboundMessage approved = inContext(ownerA, () -> commands.approve(new ApprovalCommand(pending.id(), null,
                Long.toString(pending.version()), "approve-" + UUID.randomUUID()))).message();
        assertThat(approved.status()).isEqualTo(MessageStatus.APPROVED);
        return approved;
    }

    private Customer eligibleCustomer(String name) throws Exception {
        Instant now = Instant.now();
        Customer created = inContext(ownerA, () -> customers.create(name, null,
                List.of(new PhoneInput("8888" + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000),
                        "CR", true))));
        UUID phone = created.phones().getFirst().id();
        inContext(ownerA, () -> contacts.changeConsent(created.id(), phone, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, contacts.get(created.id()).version()));
        inContext(ownerA, () -> purchases.record(created.id(), now.minusSeconds(60), "Anchor purchase",
                "anchor-" + UUID.randomUUID()));
        inContext(ownerA, () -> followUps.configureCustomer(created.id(), null, LocalDate.now(ZoneId.of("UTC")), 0));
        return created;
    }

    private void changeConsent(ConsentStatus status) throws Exception {
        inContext(ownerA, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP, status,
                ContactIntentSource.CUSTOMER_WRITTEN, contacts.get(customer.id()).version()));
    }

    private static SendMessageCommand send(OutboundMessage message, String key) {
        return new SendMessageCommand(message.id(), Long.toString(message.version()), key);
    }

    private OutboundMessage current(OutboundMessage message) throws Exception {
        return inContext(ownerA, () -> messages.findById(message.tenantId(), message.id())).orElseThrow();
    }

    private List<SendAttempt> attempts(OutboundMessage message) throws Exception {
        return inContext(ownerA, () -> messages.findAttempts(message.tenantId(), message.id()));
    }

    private List<MessageEventType> eventTypes(OutboundMessage message) throws Exception {
        return inContext(ownerA, () -> messages.findEvents(message.tenantId(), message.id())).stream()
                .map(MessageEvent::type).toList();
    }

    private int idempotencyRows(OutboundMessage message) throws Exception {
        return inContext(ownerA, () -> jdbc.queryForObject("""
                SELECT count(*) FROM dokene.outbound_message_idempotency_keys
                WHERE tenant_id = ? AND message_id = ? AND operation = 'SEND'
                """, Integer.class, message.tenantId(), message.id()));
    }

    private List<AuditEventType> auditTypes() throws Exception {
        return inContext(ownerA, () -> auditReader.read(null, 100).events()).stream().map(event -> event.type())
                .toList();
    }

    @FunctionalInterface
    private interface Racer {
        Object run(int index) throws Exception;
    }

    /** Each racer's result, or the refusal code it ended with. */
    private List<Object> race(int count, Racer racer) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int racerIndex = index;
                futures.add(pool.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        return inContext(ownerA, () -> racer.run(racerIndex));
                    } catch (MessagingRefusedException refused) {
                        return refused.code();
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
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
