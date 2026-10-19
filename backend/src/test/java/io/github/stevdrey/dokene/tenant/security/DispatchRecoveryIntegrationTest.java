// ABOUTME: Dispatch recovery against PostgreSQL (issue 138, ADR 0028 §5): an abandoned claim becomes an unknown outcome
// ABOUTME: exactly once, never with a provider call, and only while its authorizing membership may still send.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.migrationConnection;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryInt;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryUuids;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.messaging.application.ApprovalCommand;
import io.github.stevdrey.dokene.messaging.application.DispatchRecoveryProcessor;
import io.github.stevdrey.dokene.messaging.application.DispatchRecoveryProperties;
import io.github.stevdrey.dokene.messaging.application.MessageAuditPort;
import io.github.stevdrey.dokene.messaging.application.MessagingDispatchProperties;
import io.github.stevdrey.dokene.messaging.application.OutboundDispatchService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageCommandService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.messaging.application.SendMessageCommand;
import io.github.stevdrey.dokene.messaging.application.SubmitMessageCommand;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.messaging.domain.SendAttemptOutcome;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.CurrentMembershipAuthorizer;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubSafetyControl;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubTemplateResolver;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

// A zero claim timeout makes every committed claim expired at once; the database tests below pin the cutoff itself.
@SpringBootTest(properties = {"dokene.ai.provider=fake", "dokene.messaging.recovery.claim-timeout=PT0S",
        "dokene.messaging.recovery.batch-size=500"})
@Import({MappedGeneralFollowUpGateConfiguration.class, ScriptedMessagingProvider.Configuration.class})
class DispatchRecoveryIntegrationTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired DispatchRecoveryProcessor recovery;
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
    @Autowired CustomerRepository customerRepository;
    @Autowired CurrentMembershipAuthorizer currentMembers;
    @Autowired MessageAuditPort messageAudit;
    @Autowired MessagingDispatchProperties dispatchProperties;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;
    @Autowired ScheduledTaskHolder scheduledTasks;
    @MockitoSpyBean AuditRecorder auditRecorder;
    @MockitoSpyBean TenantContextResolver resolver;

    private Tenant tenantA;
    private TenantContext ownerA;

    @BeforeEach
    void setUp() throws Exception {
        MappedGeneralFollowUpGateConfiguration.reset(templates, safety);
        safety.blockedWith = null;
        provider.reset();
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, contexts, "Recovery A " + UUID.randomUUID(), now);
        ownerA = context(seedMembership(memberships, contexts, tenantA.id(), new IdentityId(UUID.randomUUID()),
                TenantRole.OWNER, now));
        inContext(ownerA, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
    }

    @Test
    void aCrashAfterSubmissionBecomesAnUnknownOutcomeWithoutAnotherProviderCall() throws Exception {
        OutboundMessage halted = haltedClaim(ownerA);

        recovery.recoverExpiredClaims();

        OutboundMessage recovered = current(halted);
        assertThat(recovered.status()).isEqualTo(MessageStatus.SENDING);
        assertThat(recovered.outcomeUnknown()).isTrue();
        assertThat(recovered.version()).isEqualTo(halted.version() + 1);
        assertThat(attempts(halted)).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo(SendAttemptOutcome.OUTCOME_UNKNOWN));
        assertThat(eventTypes(halted)).endsWith(MessageEventType.SEND_ATTEMPT_STARTED,
                MessageEventType.SEND_OUTCOME_UNKNOWN);
        // Attributed to the member who authorized the send, as the inline outcome would have been (ADR 0028 §6).
        assertThat(unknownOutcomeAudits(halted)).singleElement().satisfies(event -> {
            assertThat(event.membershipId()).isEqualTo(ownerA.membershipId());
            assertThat(event.actorId()).isEqualTo(ownerA.identityId());
            assertThat(event.correlationId()).isNotNull();
        });
        assertThat(provider.calls()).hasSize(1);

        // A later run finds nothing left to do for this message.
        recovery.recoverExpiredClaims();
        assertThat(current(halted).version()).isEqualTo(recovered.version());
        assertThat(provider.calls()).hasSize(1);
    }

    @Test
    void twoProcessorsRacingOverOneClaimRecordItOnce() throws Exception {
        // The run lock usually turns the loser away; when the runs do not overlap, the row locks and the still-claimed
        // re-check make the second run a no-op.
        OutboundMessage halted = haltedClaim(ownerA);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                runs.add(pool.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return recovery.recoverExpiredClaims();
                }));
            }
            start.countDown();
            for (Future<Integer> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(current(halted).version()).isEqualTo(halted.version() + 1);
        assertThat(eventTypes(halted)).filteredOn(MessageEventType.SEND_OUTCOME_UNKNOWN::equals).hasSize(1);
        assertThat(unknownOutcomeAudits(halted)).hasSize(1);
        assertThat(provider.calls()).hasSize(1);
    }

    @Test
    void aRevokedOrDemotedAuthorizingMembershipLeavesTheClaimUntouched() throws Exception {
        Instant now = Instant.now();
        TenantMembership revoked = seedMembership(memberships, contexts, tenantA.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.ADMIN, now);
        TenantMembership demoted = seedMembership(memberships, contexts, tenantA.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.ADMIN, now);
        OutboundMessage haltedByRevoked = haltedClaim(context(revoked));
        OutboundMessage haltedByDemoted = haltedClaim(context(demoted));
        revoked.revoke(Instant.now());
        demoted.changeRole(TenantRole.VIEWER, Instant.now());
        contexts.runWithTenantId(tenantA.id(), () -> {
            memberships.save(revoked);
            memberships.save(demoted);
        });

        recovery.recoverExpiredClaims();

        for (OutboundMessage halted : List.of(haltedByRevoked, haltedByDemoted)) {
            OutboundMessage after = current(halted);
            assertThat(after.version()).isEqualTo(halted.version());
            assertThat(after.outcomeUnknown()).isFalse();
            assertThat(attempts(halted)).singleElement()
                    .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo(SendAttemptOutcome.STARTED));
            assertThat(unknownOutcomeAudits(halted)).isEmpty();
        }
        assertThat(provider.calls()).hasSize(2);
    }

    @Test
    void claimsThatMustBeLeftAloneCannotStarveTheOthers() throws Exception {
        TenantMembership revoked = seedMembership(memberships, contexts, tenantA.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.ADMIN, Instant.now());
        haltedClaim(context(revoked));
        haltedClaim(context(revoked));
        revoked.revoke(Instant.now());
        contexts.runWithTenantId(tenantA.id(), () -> memberships.save(revoked));
        OutboundMessage recoverable = haltedClaim(ownerA);
        // One claim per run: oldest first, the blocked claims (here and from earlier tests) would fill every run.
        DispatchRecoveryProcessor oneAtATime = processor(new DispatchRecoveryProperties(false, Duration.ZERO, 1,
                Duration.ofMinutes(1)));

        int runs = startedClaims() + 1;
        for (int run = 0; run < runs && !current(recoverable).outcomeUnknown(); run++) {
            oneAtATime.recoverExpiredClaims();
        }

        assertThat(current(recoverable).outcomeUnknown()).isTrue();
        assertThat(provider.calls()).hasSize(3);
    }

    @Test
    void aMembershipRevokedAfterTheFirstCheckIsCaughtInsideTheTransaction() throws Exception {
        TenantMembership member = seedMembership(memberships, contexts, tenantA.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.ADMIN, Instant.now());
        OutboundMessage halted = haltedClaim(context(member));
        // The check that builds the context passes; the revocation commits before the transaction starts.
        doAnswer(invocation -> {
            Object resolved = invocation.callRealMethod();
            member.revoke(Instant.now());
            contexts.runWithTenantId(tenantA.id(), () -> memberships.save(member));
            return resolved;
        }).doCallRealMethod().when(resolver).resolve(eq(member.identityId()), eq(tenantA.id()));

        recovery.recoverExpiredClaims();

        assertThat(current(halted).version()).isEqualTo(halted.version());
        assertThat(current(halted).outcomeUnknown()).isFalse();
        assertThat(attempts(halted)).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo(SendAttemptOutcome.STARTED));
        assertThat(inContext(ownerA, () -> auditReader.read(null, 100).events())).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo(AuditEventType.AUTHORIZATION_DENIED);
            assertThat(event.membershipId()).isEqualTo(member.id());
        });
    }

    @Test
    void anEnabledClaimTimeoutMustExceedTheDispatchDeadlineByTheMargin() {
        MessagingDispatchProperties tenSeconds = new MessagingDispatchProperties(null, null, null,
                Duration.ofSeconds(10));
        // A claim's start is stamped before its commit and the call, so only a margin past the deadline is safe.
        for (Duration tooShort : List.of(Duration.ofSeconds(10), Duration.ofSeconds(11), Duration.ofSeconds(69))) {
            assertThatThrownBy(() -> processor(new DispatchRecoveryProperties(true, tooShort, null, null),
                    tenSeconds)).isInstanceOf(IllegalStateException.class).hasMessageContaining("claim-timeout");
        }
        assertThat(processor(new DispatchRecoveryProperties(true, Duration.ofSeconds(70), null, null), tenSeconds))
                .isNotNull();
        assertThat(processor(new DispatchRecoveryProperties(true, Duration.ofMinutes(10), null, null), tenSeconds))
                .isNotNull();
        // Disabled recovery never runs, so its timeout is not checked.
        assertThat(processor(new DispatchRecoveryProperties(false, Duration.ZERO, null, null), tenSeconds))
                .isNotNull();
    }

    @Test
    void theScheduledRunIsRegisteredWhateverTheRecoveryFlag() {
        // Scheduling is application wide; with recovery disabled (as in tests) the run is registered and returns early.
        assertThat(scheduledTasks.getScheduledTasks()).map(task -> task.getTask().toString())
                .anySatisfy(task -> assertThat(task).contains(DispatchRecoveryProcessor.class.getName())
                        .contains("scheduledRun"));
    }

    @Test
    void onlyOneInstanceRunsRecoveryAtATime() throws Exception {
        OutboundMessage halted = haltedClaim(ownerA);
        String lock = "(hashtext('dokene'), hashtext('DISPATCH_RECOVERY'))";

        try (Connection otherInstance = runtimeConnectionWithoutContext()) {
            assertThat(queryInt(otherInstance, "SELECT pg_try_advisory_lock" + lock + "::int")).isEqualTo(1);
            assertThat(recovery.recoverExpiredClaims()).isZero();
            assertThat(current(halted).version()).isEqualTo(halted.version());
            assertThat(queryInt(otherInstance, "SELECT pg_advisory_unlock" + lock + "::int")).isEqualTo(1);
        }

        recovery.recoverExpiredClaims();
        assertThat(current(halted).outcomeUnknown()).isTrue();
    }

    @Test
    void anAuditOutageDuringRecoveryKeepsTheClaimForTheNextRun() throws Exception {
        OutboundMessage halted = haltedClaim(ownerA);
        doThrow(new AuditPersistenceException()).when(auditRecorder)
                .messageTransition(any(), eq(AuditEventType.MESSAGE_SEND_OUTCOME_UNKNOWN), any());

        recovery.recoverExpiredClaims();

        assertThat(current(halted).version()).isEqualTo(halted.version());
        assertThat(attempts(halted)).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo(SendAttemptOutcome.STARTED));

        reset(auditRecorder);
        recovery.recoverExpiredClaims();

        assertThat(current(halted).outcomeUnknown()).isTrue();
        assertThat(provider.calls()).hasSize(1);
    }

    @Test
    void theExpiredClaimFinderIsTheOnlyCrossTenantReadAndExposesIdsOnly() throws Exception {
        OutboundMessage halted = haltedClaim(ownerA);
        SendAttempt claim = attempts(halted).getFirst();
        Timestamp afterClaim = Timestamp.from(claim.startedAt().plusSeconds(1));
        Timestamp beforeClaim = Timestamp.from(claim.startedAt().minusSeconds(1));
        String finder = "SELECT message_id FROM dokene.find_expired_dispatch_claims(?::timestamptz, ?::uuid, "
                + "?::integer)";
        String attemptIds = "SELECT attempt_id FROM dokene.find_expired_dispatch_claims(?::timestamptz, ?::uuid, "
                + "?::integer)";

        try (Connection runtime = runtimeConnectionWithoutContext()) {
            // Tenant RLS hides the attempt itself; the definer function returns only its ids, by attempt id.
            assertThat(queryInt(runtime, "SELECT count(*) FROM dokene.outbound_send_attempts")).isZero();
            assertThat(queryUuids(runtime, finder, afterClaim, null, 500)).contains(halted.id());
            assertThat(queryUuids(runtime, finder, beforeClaim, null, 500)).doesNotContain(halted.id());
            assertThat(queryUuids(runtime, finder, afterClaim, null, 1)).hasSizeLessThanOrEqualTo(1);
            assertThat(queryUuids(runtime, finder, afterClaim, null, 0)).isEmpty();
            assertThat(queryUuids(runtime, finder, null, null, 500)).isEmpty();
            // The cursor resumes strictly after the given attempt id, in PostgreSQL's byte order (the canonical
            // text order); java.util.UUID compares signed halves, so it cannot be used here.
            assertThat(queryUuids(runtime, attemptIds, afterClaim, claim.id(), 500)).doesNotContain(claim.id())
                    .allSatisfy(id -> assertThat(id.toString()).isGreaterThan(claim.id().toString()));
            assertThat(queryUuids(runtime, attemptIds, afterClaim, new UUID(0, 0), 500)).contains(claim.id());
        }
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_proc WHERE proname = ? AND prosecdef",
                    "find_expired_dispatch_claims")).isEqualTo(1);
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_proc p WHERE p.proname = ? "
                    + "AND has_function_privilege('public', p.oid, 'EXECUTE')",
                    "find_expired_dispatch_claims")).isZero();
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_proc p WHERE p.proname = ? "
                    + "AND has_function_privilege('dokene_runtime', p.oid, 'EXECUTE')",
                    "find_expired_dispatch_claims")).isEqualTo(1);
            // The result shape is (tenant_id, message_id, attempt_id) and nothing else.
            assertThat(queryInt(migration, "SELECT cardinality(proargnames) FROM pg_proc WHERE proname = ?",
                    "find_expired_dispatch_claims")).isEqualTo(6);
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_proc WHERE proname = ? AND proargnames = "
                    + "ARRAY['started_before', 'after_attempt_id', 'max_rows', 'tenant_id', 'message_id', "
                    + "'attempt_id']", "find_expired_dispatch_claims")).isEqualTo(1);
        }
    }

    @Test
    void recoveryRefusesToRunInsideATenantScope() {
        assertThatThrownBy(() -> contexts.callWithTenantId(tenantA.id(), recovery::recoverExpiredClaims))
                .isInstanceOf(IllegalStateException.class);
    }

    private DispatchRecoveryProcessor processor(DispatchRecoveryProperties properties) {
        return processor(properties, dispatchProperties);
    }

    private DispatchRecoveryProcessor processor(DispatchRecoveryProperties properties,
            MessagingDispatchProperties dispatchBounds) {
        return new DispatchRecoveryProcessor(messages, customerRepository, contexts, currentMembers,
                auditExecution, messageAudit, properties, dispatchBounds, transactionManager, clock);
    }

    private int startedClaims() throws Exception {
        try (Connection migration = migrationConnection()) {
            return queryInt(migration, "SELECT count(*) FROM dokene.outbound_send_attempts WHERE outcome = 'STARTED'");
        }
    }

    /** A message whose outcome transaction failed after the provider accepted it: SENDING with a STARTED claim. */
    private OutboundMessage haltedClaim(TenantContext member) throws Exception {
        OutboundMessage approved = approvedMessage(member);
        doThrow(new AuditPersistenceException()).when(auditRecorder)
                .messageTransition(any(), eq(AuditEventType.MESSAGE_SENT), any());
        try {
            assertThatThrownBy(() -> inContext(member, () -> dispatch.send(new SendMessageCommand(approved.id(),
                    Long.toString(approved.version()), "halt-" + UUID.randomUUID()))))
                    .isInstanceOf(AuditPersistenceException.class);
        } finally {
            reset(auditRecorder);
        }
        OutboundMessage halted = current(approved);
        assertThat(halted.status()).isEqualTo(MessageStatus.SENDING);
        assertThat(attempts(halted)).singleElement()
                .satisfies(attempt -> assertThat(attempt.outcome()).isEqualTo(SendAttemptOutcome.STARTED));
        return halted;
    }

    private OutboundMessage approvedMessage(TenantContext member) throws Exception {
        Customer customer = eligibleCustomer();
        UUID contactId = customer.phones().getFirst().id();
        long policyVersion = inContext(member, () -> followUps.evaluateSnapshot(customer.id())).policyVersion();
        OutboundMessage pending = inContext(member, () -> commands.submit(new SubmitMessageCommand(
                customer.id().value(), contactId, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", BODY, "es-419", "MANUAL",
                Long.toString(policyVersion), "submit-" + UUID.randomUUID()))).message();
        return inContext(member, () -> commands.approve(new ApprovalCommand(pending.id(), null,
                Long.toString(pending.version()), "approve-" + UUID.randomUUID()))).message();
    }

    private Customer eligibleCustomer() throws Exception {
        Instant now = Instant.now();
        Customer created = inContext(ownerA, () -> customers.create("Recovery customer " + UUID.randomUUID(), null,
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

    private List<AuditEvent> unknownOutcomeAudits(OutboundMessage message) throws Exception {
        return inContext(ownerA, () -> auditReader.read(null, 100).events()).stream()
                .filter(event -> event.type() == AuditEventType.MESSAGE_SEND_OUTCOME_UNKNOWN)
                .filter(event -> event.target() != null && message.id().equals(event.target().id())).toList();
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }
}
