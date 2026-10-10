// ABOUTME: Drives POST /sends against Postgres with a spy provider: three phase shape, replay, outcomes, refusals.
// ABOUTME: The spy is the only MessagingProvider bean (provider property blank), so it counts every provider call.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpPolicyRepository;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.integration.ProviderReportExecution;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusSink;
import io.github.stevdrey.dokene.messaging.application.MessagingProvider;
import io.github.stevdrey.dokene.messaging.application.OutboundGate;
import io.github.stevdrey.dokene.messaging.application.OutboundGateStatus;
import io.github.stevdrey.dokene.messaging.application.OutboundSendCommand;
import io.github.stevdrey.dokene.messaging.application.OutboundSendRateLimiter;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"dokene.ai.provider=fake",
        "dokene.messaging.template-gate.stub.enabled-intents=GENERAL_FOLLOW_UP",
        "dokene.messaging.outbound.enabled=true", "dokene.messaging.outbound-gate.stub.enabled=true"})
@Import(OutboundSendIntegrationTest.SpyProviderConfiguration.class)
class OutboundSendIntegrationTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";

    @TestConfiguration
    static class SpyProviderConfiguration {
        @Bean
        RecordingProvider recordingProvider() {
            return new RecordingProvider();
        }
    }

    /** The only MessagingProvider bean: counts calls, records commands, and does whatever the test says. */
    static final class RecordingProvider implements MessagingProvider {
        final AtomicInteger calls = new AtomicInteger();
        final List<OutboundSendCommand> commands = new CopyOnWriteArrayList<>();
        volatile Function<OutboundSendCommand, ProviderSendResult> behaviour = accept();

        static Function<OutboundSendCommand, ProviderSendResult> accept() {
            return command -> new ProviderSendResult.Accepted("spy-" + UUID.randomUUID());
        }

        @Override
        public ProviderSendResult send(OutboundSendCommand command) {
            calls.incrementAndGet();
            commands.add(command);
            return behaviour.apply(command);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    MockMvc mvc;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired ObjectMapper json;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired CustomerService customers;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired FollowUpService followUps;
    @Autowired FollowUpPolicyRepository policies;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingProvider provider;
    @Autowired DeliveryStatusSink sink;
    @Autowired ProviderReportExecution providerExecution;
    @Autowired MeterRegistry registry;
    @MockitoSpyBean AuditRecorder auditRecorder;
    @MockitoSpyBean OutboundGate gate;
    @MockitoSpyBean OutboundSendRateLimiter rateLimiter;

    private Tenant tenantA;
    private Tenant tenantB;
    private IdentityId ownerA;
    private IdentityId viewerA;
    private IdentityId ownerB;
    private TenantContext ownerAContext;
    private TenantContext ownerBContext;
    private Seeded seeded;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger messagingLogger = (Logger) LoggerFactory.getLogger("io.github.stevdrey.dokene.messaging");

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        provider.calls.set(0);
        provider.commands.clear();
        provider.behaviour = RecordingProvider.accept();
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, "Sends A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, "Sends B " + UUID.randomUUID(), now);
        ownerA = new IdentityId(UUID.randomUUID());
        viewerA = new IdentityId(UUID.randomUUID());
        ownerB = new IdentityId(UUID.randomUUID());
        ownerAContext = context(seedMembership(memberships, contexts, tenantA.id(), ownerA, TenantRole.OWNER, now));
        seedMembership(memberships, contexts, tenantA.id(), viewerA, TenantRole.VIEWER, now);
        ownerBContext = context(seedMembership(memberships, contexts, tenantB.id(), ownerB, TenantRole.OWNER, now));
        inContext(ownerAContext, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        seeded = seedCustomer("Cliente principal");
        logs.start();
        messagingLogger.addAppender(logs);
    }

    /** AC-9 across the suite: no body, phone, provider id or idempotency key reaches the messaging logs. */
    @AfterEach
    void detachLogsAndProveTheyAreClean() {
        messagingLogger.detachAppender(logs);
        logs.stop();
        for (ILoggingEvent entry : logs.list) {
            assertThat(entry.getFormattedMessage()).doesNotContain(BODY).doesNotContain(seeded.phone())
                    .doesNotContain("spy-").doesNotContain("send-");
        }
    }

    @Test
    void sendsAnApprovedMessageInThreePhasesReplaysByKeyAndTouchesTheCadence() throws Exception {
        UUID messageId = approved(seeded);
        String key = "send-" + UUID.randomUUID();

        MvcResult first = mvc.perform(send(messageId, "\"2\"", key)).andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"5\"")).andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.outcomeUnknown").value(false))
                .andExpect(jsonPath("$.providerMessageId").value(org.hamcrest.Matchers.startsWith("spy-")))
                .andExpect(jsonPath("$.sentAt").isNotEmpty())
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.attempts[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.lastAttempt.attemptNumber").value(1))
                .andReturn();
        assertThat(provider.calls.get()).isEqualTo(1);
        OutboundSendCommand command = provider.commands.getFirst();
        assertThat(command.recipientPhone()).isEqualTo(seeded.phone());
        assertThat(command.providerTemplateName()).isEqualTo("general_follow_up");
        assertThat(command.parameters()).containsExactly(BODY);
        assertThat(command.tenantId()).isEqualTo(tenantA.id().value());
        assertThat(auditRows(messageId, "MESSAGE_SEND_REQUESTED")).isEqualTo(1);
        assertThat(auditRows(messageId, "MESSAGE_SENT")).isEqualTo(1);
        assertThat(rowCount("outbound_send_attempts", messageId)).isEqualTo(1);
        var evaluation = inContext(ownerAContext, () -> followUps.evaluateSnapshot(seeded.customer().id()));
        assertThat(evaluation.evaluation().status()).isEqualTo(FollowUpStatus.NOT_YET_DUE);

        MvcResult replay = mvc.perform(send(messageId, "\"2\"", key)).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"5\"")).andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(rowCount("outbound_send_attempts", messageId)).isEqualTo(1);
        assertThat(auditRows(messageId, "MESSAGE_SEND_REQUESTED")).isEqualTo(1);
        verify(rateLimiter, times(1)).acquire(any());

        mvc.perform(send(messageId, "\"5\"", key)).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(send(messageId, "\"5\"", "send-" + UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "INVALID_TRANSITION"));
        assertThat(provider.calls.get()).isEqualTo(1);

        mvc.perform(get("/api/messages/{id}/events", messageId).with(user(ownerA))
                        .header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events.length()").value(5))
                .andExpect(jsonPath("$.events[2].eventType").value("SEND_REQUESTED"))
                .andExpect(jsonPath("$.events[2].actorKind").value("MEMBER"))
                .andExpect(jsonPath("$.events[3].eventType").value("SEND_ATTEMPT_STARTED"))
                .andExpect(jsonPath("$.events[3].actorKind").value("SYSTEM"))
                .andExpect(jsonPath("$.events[4].eventType").value("SENT"))
                .andExpect(jsonPath("$.events[4].statusTo").value("SENT"));

        var counter = registry.find("dokene.messaging.send.attempts").tag("outcome", "ACCEPTED").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("outcome"));
        assertThat(registry.find("dokene.messaging.send.duration").timer()).isNotNull();
    }

    @Test
    void commitsTheAttemptBeforeTheProviderCallAndFreezesWhenTheAdapterThrows() throws Exception {
        UUID messageId = approved(seeded);
        provider.behaviour = command -> {
            // Phase 2 holds no transaction, so this read uses a fresh connection and sees only committed rows.
            assertThat(jdbc.queryForObject("SELECT status FROM dokene.outbound_messages WHERE id = ?",
                    String.class, messageId)).isEqualTo("SENDING");
            assertThat(jdbc.queryForObject("SELECT outcome FROM dokene.outbound_send_attempts WHERE message_id = ?",
                    String.class, messageId)).isEqualTo("STARTED");
            throw new IllegalStateException("connection reset");
        };
        String key = "send-" + UUID.randomUUID();

        mvc.perform(send(messageId, "\"2\"", key)).andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(jsonPath("$.status").value("SENDING"))
                .andExpect(jsonPath("$.outcomeUnknown").value(true))
                .andExpect(jsonPath("$.providerMessageId").value((Object) null))
                .andExpect(jsonPath("$.attempts[0].outcome").value("OUTCOME_UNKNOWN"));
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(auditRows(messageId, "MESSAGE_SEND_OUTCOME_UNKNOWN")).isEqualTo(1);

        mvc.perform(send(messageId, "\"5\"", "send-" + UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "INVALID_TRANSITION"));
        mvc.perform(send(messageId, "\"2\"", key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENDING"));
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(logs.list).anySatisfy(entry -> assertThat(entry.getFormattedMessage())
                .contains("IllegalStateException").contains(messageId.toString()));
    }

    @Test
    void everyProviderOutcomeLandsWhereTheStateMachineSays() throws Exception {
        Seeded rejected = seedCustomer("Rechazado");
        UUID rejectedId = approved(rejected);
        provider.behaviour = command -> new ProviderSendResult.RejectedPermanently(FailureCategory.INVALID_RECIPIENT);
        mvc.perform(send(rejectedId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.lastAttempt.outcome").value("FAILED_PERMANENT"))
                .andExpect(jsonPath("$.lastAttempt.failureCategory").value("INVALID_RECIPIENT"));
        assertThat(auditRows(rejectedId, "MESSAGE_SEND_FAILED")).isEqualTo(1);

        Seeded transientCustomer = seedCustomer("Transitorio");
        UUID transientId = approved(transientCustomer);
        provider.behaviour = command -> new ProviderSendResult.FailedTransiently(FailureCategory.RATE_LIMITED);
        mvc.perform(send(transientId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"5\"")).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.lastAttempt.outcome").value("FAILED_TRANSIENT"));
        mvc.perform(send(transientId, "\"5\"", "send-" + UUID.randomUUID())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.lastAttempt.attemptNumber").value(2));
        mvc.perform(send(transientId, "\"8\"", "send-" + UUID.randomUUID())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.attempts.length()").value(3))
                .andExpect(jsonPath("$.lastAttempt.attemptNumber").value(3));
        mvc.perform(send(transientId, "\"11\"", "send-" + UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "INVALID_TRANSITION"));
        assertThat(auditRows(transientId, "MESSAGE_SEND_FAILED")).isEqualTo(3);

        Seeded unknown = seedCustomer("Desconocido");
        UUID unknownId = approved(unknown);
        provider.behaviour = command -> new ProviderSendResult.OutcomeUnknown(Optional.of("spy-timeout"));
        mvc.perform(send(unknownId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SENDING"))
                .andExpect(jsonPath("$.outcomeUnknown").value(true))
                .andExpect(jsonPath("$.providerMessageId").value("spy-timeout"));
        assertThat(provider.calls.get()).isEqualTo(5);
    }

    @Test
    void aFailingCompletionIsVisibleAndLeavesSendingWithAStartedAttempt() throws Exception {
        UUID messageId = approved(seeded);
        doThrow(new AuditPersistenceException()).when(auditRecorder)
                .messageTransition(any(), eq(AuditEventType.MESSAGE_SENT), any());
        double before = counter("COMPLETION_FAILED");

        mvc.perform(send(messageId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isServiceUnavailable());

        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(column("outbound_messages", "status", messageId)).isEqualTo("SENDING");
        assertThat(column("outbound_send_attempts", "outcome", messageId)).isEqualTo("STARTED");
        assertThat(counter("COMPLETION_FAILED")).isEqualTo(before + 1);
        assertThat(logs.list).anySatisfy(entry -> {
            assertThat(entry.getLevel().toString()).isEqualTo("ERROR");
            assertThat(entry.getFormattedMessage()).contains(messageId.toString())
                    .contains(tenantA.id().value().toString());
        });
    }

    @Test
    void refusesASendAfterTheAnchorDateAndNeverBlocksOnTheSendThatSetIt() throws Exception {
        // An old purchase, so the outbound message date (not the purchase) drives the anchor in this test.
        Seeded seeded = seedCustomer("Frecuencia", Duration.ofDays(60));
        UUID first = approved(seeded);
        mvc.perform(send(first, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SENT"));
        String providerMessageId = column("outbound_messages", "provider_message_id", first);
        for (DeliveryStatus status : List.of(DeliveryStatus.DELIVERED, DeliveryStatus.READ)) {
            providerExecution.call(tenantA.id().value(), () -> sink.apply(
                    new DeliveryStatusReport(providerMessageId, status, Instant.now(), Optional.empty())));
        }
        assertThat(column("outbound_messages", "status", first)).isEqualTo("READ");

        // The sent message is on the anchor date (today), so a cadence anchored today lets a later send through.
        explicitDateToday(seeded);
        Seeded refreshed = reseed(seeded);
        UUID second = approved(refreshed);
        mvc.perform(send(second, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SENT"));
        String secondProviderId = column("outbound_messages", "provider_message_id", second);
        for (DeliveryStatus status : List.of(DeliveryStatus.DELIVERED, DeliveryStatus.READ)) {
            providerExecution.call(tenantA.id().value(), () -> sink.apply(
                    new DeliveryStatusReport(secondProviderId, status, Instant.now(), Optional.empty())));
        }

        // Drift the anchor to yesterday (the repository itself only moves it forward): the message sent today is
        // now strictly after it, so step 8 refuses. This is the stale policy row the guard exists for.
        inContext(ownerAContext, () -> jdbc.update("""
                UPDATE dokene.customer_follow_up_policies SET last_outbound_message_date = ?, version = version + 1
                WHERE tenant_id = ? AND customer_id = ?
                """, java.sql.Date.valueOf(LocalDate.now(ZoneId.of("UTC")).minusDays(1)), tenantA.id().value(),
                seeded.customer().id().value()));
        assertThat(inContext(ownerAContext, () -> policies.customerPolicy(tenantA.id(), seeded.customer().id()))
                .lastOutboundMessageDate()).isEqualTo(LocalDate.now(ZoneId.of("UTC")).minusDays(1));
        explicitDateToday(seeded);
        Seeded again = reseed(seeded);
        UUID third = approved(again);
        mvc.perform(send(third, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "CONTACT_FREQUENCY_EXCEEDED"));
        assertThat(rowCount("outbound_send_attempts", third)).isZero();
        assertThat(provider.calls.get()).isEqualTo(2);
    }

    @Test
    void refusesTheSwitchesTheRateLimitAndRevokedConsentWithoutWritingAnything() throws Exception {
        UUID messageId = approved(seeded);

        doReturn(OutboundGateStatus.OUTBOUND_DISABLED_FOR_TENANT).when(gate).check();
        mvc.perform(send(messageId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "OUTBOUND_DISABLED_FOR_TENANT"));
        doReturn(OutboundGateStatus.INTEGRATION_DISABLED).when(gate).check();
        mvc.perform(send(messageId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "INTEGRATION_DISABLED"));
        doReturn(OutboundGateStatus.ENABLED).when(gate).check();

        doReturn(Optional.of(Duration.ofSeconds(17))).when(rateLimiter).acquire(any());
        mvc.perform(send(messageId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "17")).andExpect(problem(429, "Too Many Requests",
                        "RATE_LIMITED"));
        doReturn(Optional.empty()).when(rateLimiter).acquire(any());

        var policy = inContext(ownerAContext, () -> contacts.get(seeded.customer().id()));
        inContext(ownerAContext, () -> contacts.changeConsent(seeded.customer().id(), seeded.contactId(),
                ContactChannel.WHATSAPP, ConsentStatus.REVOKED, ContactIntentSource.CUSTOMER_WRITTEN,
                policy.version()));
        mvc.perform(send(messageId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "NO_CONTACT_CONSENT"));

        assertThat(provider.calls.get()).isZero();
        assertThat(rowCount("outbound_send_attempts", messageId)).isZero();
        assertThat(rowCount("outbound_message_events", messageId)).isEqualTo(2);
        mvc.perform(get("/api/messages/{id}", messageId).with(user(ownerA)).header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // A sent key replays even once the gate closes again: the key lookup runs before every switch.
        Seeded other = seedCustomer("Otro cliente");
        UUID sentId = approved(other);
        String key = "send-" + UUID.randomUUID();
        mvc.perform(send(sentId, "\"2\"", key)).andExpect(status().isCreated());
        doReturn(OutboundGateStatus.OUTBOUND_DISABLED_FOR_TENANT).when(gate).check();
        mvc.perform(send(sentId, "\"2\"", key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    void viewerAndForeignTenantAreRefusedBeforeAnyProviderCall() throws Exception {
        UUID messageId = approved(seeded);

        mvc.perform(post("/api/messages/{id}/sends", messageId).with(user(viewerA)).with(csrf())
                        .header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isForbidden()).andExpect(problem(403, "Forbidden", "FORBIDDEN"));
        verify(auditRecorder, times(1)).authorizationDenied(eq(TenantPermission.MESSAGE_SEND), any());
        verify(auditRecorder, times(1)).authorizationDenied(any(), any());

        mvc.perform(post("/api/messages/{id}/sends", messageId).with(user(ownerB)).with(csrf())
                        .header("X-Tenant-Id", tenantB.id().value()).header("If-Match", "\"2\"")
                        .header("Idempotency-Key", "send-" + UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(problem(404, "Not Found", "NOT_FOUND"));

        assertThat(provider.calls.get()).isZero();
        assertThat(rowCount("outbound_send_attempts", messageId)).isZero();
        assertThat(rowCountAs(ownerBContext, "outbound_messages", messageId)).isZero();
    }

    @Test
    void concurrentSendsWithDifferentKeysYieldOneSentAndOneStaleVersion() throws Exception {
        UUID messageId = approved(seeded);
        provider.behaviour = command -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new ProviderSendResult.Accepted("spy-" + UUID.randomUUID());
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> mvc.perform(send(messageId, "\"2\"", "send-" + UUID.randomUUID()))
                    .andReturn().getResponse().getStatus();
            Future<Integer> one = pool.submit(attempt);
            Future<Integer> two = pool.submit(attempt);
            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(rowCount("outbound_send_attempts", messageId)).isEqualTo(1);
        mvc.perform(get("/api/messages/{id}", messageId).with(user(ownerA)).header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));
    }

    private double counter(String outcome) {
        var found = registry.find("dokene.messaging.send.attempts").tag("outcome", outcome).counter();
        return found == null ? 0.0 : found.count();
    }

    private UUID approved(Seeded target) throws Exception {
        MvcResult result = mvc.perform(submit(target, "submit-" + UUID.randomUUID(), BODY))
                .andExpect(status().isCreated()).andReturn();
        UUID messageId = UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .get("messageId").asString());
        mvc.perform(post("/api/messages/{id}/approvals", messageId).with(user(ownerA)).with(csrf())
                        .header("X-Tenant-Id", tenantA.id().value()).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "approve-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"2\""));
        return messageId;
    }

    private MockHttpServletRequestBuilder send(UUID messageId, String ifMatch, String key) {
        return post("/api/messages/{id}/sends", messageId).with(user(ownerA)).with(csrf())
                .header("X-Tenant-Id", tenantA.id().value()).header("If-Match", ifMatch)
                .header("Idempotency-Key", key);
    }

    private MockHttpServletRequestBuilder submit(Seeded target, String key, String body) {
        return post("/api/customers/{id}/messages", target.customer().id().value()).with(user(ownerA)).with(csrf())
                .header("X-Tenant-Id", tenantA.id().value()).header("If-Match", "\"" + target.policyVersion() + "\"")
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"contactId":"%s","action":"GENERAL_CHECK_IN","templateIntent":"GENERAL_FOLLOW_UP",
                         "body":"%s","locale":"es-419","origin":"MANUAL"}
                        """.formatted(target.contactId(), body));
    }

    private static ResultMatcher problem(int status, String title, String code) {
        return result -> {
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            content().json("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\"}"
                    .formatted(title, status, code), JsonCompareMode.STRICT).match(result);
        };
    }

    private Seeded seedCustomer(String displayName) throws Exception {
        return seedCustomer(displayName, Duration.ofSeconds(60));
    }

    private Seeded seedCustomer(String displayName, Duration purchaseAge) throws Exception {
        Instant now = Instant.now();
        String number = "8888" + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000);
        Customer customer = inContext(ownerAContext, () -> customers.create(displayName, null,
                List.of(new PhoneInput(number, "CR", true))));
        UUID contactId = customer.phones().getFirst().id();
        var policy = inContext(ownerAContext, () -> contacts.get(customer.id()));
        inContext(ownerAContext, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
        inContext(ownerAContext, () -> purchases.record(customer.id(), now.minus(purchaseAge), "Anchor purchase",
                "anchor-" + UUID.randomUUID()));
        inContext(ownerAContext, () -> followUps.configureCustomer(customer.id(), null,
                LocalDate.now(ZoneId.of("UTC")), 0));
        return reseed(new Seeded(customer, contactId, "+506" + number, 0));
    }

    /** Pins the explicit next date to today at the current policy version so the customer is DUE again. */
    private void explicitDateToday(Seeded target) throws Exception {
        long version = inContext(ownerAContext, () -> followUps.evaluateSnapshot(target.customer().id()))
                .policyVersion();
        inContext(ownerAContext, () -> followUps.configureCustomer(target.customer().id(), null,
                LocalDate.now(ZoneId.of("UTC")), version));
    }

    /** Re reads the policy version after a change, asserting the customer is eligible. */
    private Seeded reseed(Seeded target) throws Exception {
        var snapshot = inContext(ownerAContext, () -> followUps.evaluateSnapshot(target.customer().id()));
        assertThat(snapshot.evaluation().eligible()).isTrue();
        return new Seeded(target.customer(), target.contactId(), target.phone(), snapshot.policyVersion());
    }

    private String column(String table, String column, UUID messageId) throws Exception {
        String key = table.equals("outbound_messages") ? "id" : "message_id";
        return inContext(ownerAContext, () -> jdbc.queryForObject(
                "SELECT " + column + "::text FROM dokene." + table + " WHERE " + key + " = ?", String.class, messageId));
    }

    private int rowCount(String table, UUID messageId) throws Exception {
        return rowCountAs(ownerAContext, table, messageId);
    }

    private int rowCountAs(TenantContext context, String table, UUID messageId) throws Exception {
        String column = table.equals("outbound_messages") ? "id" : "message_id";
        return inContext(context, () -> jdbc.queryForObject(
                "SELECT count(*) FROM dokene." + table + " WHERE " + column + " = ?", Integer.class, messageId));
    }

    private int auditRows(UUID messageId, String eventType) throws Exception {
        return inContext(ownerAContext, () -> jdbc.queryForObject(
                "SELECT count(*) FROM dokene.audit_events WHERE target_id = ? AND event_type::text = ?", Integer.class,
                messageId, eventType));
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }

    private RequestPostProcessor user(IdentityId identityId) {
        var principal = new TestPrincipal(identityId);
        return authentication(new UsernamePasswordAuthenticationToken(principal, "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }

    private record Seeded(Customer customer, UUID contactId, String phone, long policyVersion) { }
}
