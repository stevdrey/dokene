// ABOUTME: Drives the Meta webhook route end to end against Postgres (issue 139, ADR 0030): signed callbacks, the
// ABOUTME: verified mapping gate, the durable inbox, dedup, reordering, the T8 race, audit outage, retention and RLS.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.executeUpdate;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.migrationConnection;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryInt;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.FollowUpTouchRecorder;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.WebhookInboxProcessor;
import io.github.stevdrey.dokene.messaging.application.ApprovalCommand;
import io.github.stevdrey.dokene.messaging.application.MessageAuditPort;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageCommandService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageTestDriver;
import io.github.stevdrey.dokene.messaging.application.SubmitMessageCommand;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.integration.testsupport.MetaCallbacks;
import io.github.stevdrey.dokene.integration.testsupport.WebhookSigning;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubSafetyControl;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubTemplateResolver;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {"dokene.ai.provider=fake", "dokene.integration.whatsapp.webhook.inbox.enabled=false",
        "dokene.integration.whatsapp.webhook.inbox.inline-limit=2"})
@Import(MappedGeneralFollowUpGateConfiguration.class)
class WhatsAppWebhookIntegrationTest {
    /** The synthetic key of contracts/whatsapp/graph-webhook-events.json; never a real Meta app secret. */
    private static final char[] APP_SECRET = "SYNTHETIC-APP-SECRET-NOT-REAL-0001".toCharArray();
    private static final String VERIFY_TOKEN = "SYNTHETIC-VERIFY-TOKEN-0001";
    private static final String PATH = "/webhooks/whatsapp";
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("dokene.integration.secrets.source", () -> "local");
        registry.add("dokene.integration.secrets.local.entries[0].scope", () -> "deployment");
        registry.add("dokene.integration.secrets.local.entries[0].ref", () -> "webhook-app-secret");
        registry.add("dokene.integration.secrets.local.entries[0].purpose", () -> "APP_SECRET");
        registry.add("dokene.integration.secrets.local.entries[0].value", () -> new String(APP_SECRET));
        registry.add("dokene.integration.secrets.local.entries[1].scope", () -> "deployment");
        registry.add("dokene.integration.secrets.local.entries[1].ref", () -> "webhook-verify-token");
        registry.add("dokene.integration.secrets.local.entries[1].purpose", () -> "WEBHOOK_VERIFY_TOKEN");
        registry.add("dokene.integration.secrets.local.entries[1].value", () -> VERIFY_TOKEN);
        registry.add("dokene.integration.whatsapp.app-secret-ref", () -> "webhook-app-secret");
        registry.add("dokene.integration.whatsapp.verify-token-ref", () -> "webhook-verify-token");
        registry.add("dokene.integration.whatsapp.webhook.max-body-bytes", () -> "65536");
    }

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired WebhookInboxProcessor inbox;
    @Autowired OutboundMessageCommandService commands;
    @Autowired OutboundMessageRepository messages;
    @Autowired MessageAuditPort messageAudit;
    @Autowired FollowUpService followUps;
    @Autowired FollowUpTouchRecorder touches;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired CustomerService customers;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired StubTemplateResolver templates;
    @Autowired StubSafetyControl safety;
    @Autowired MeterRegistry meters;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @MockitoSpyBean AuditRecorder auditRecorder;

    private MockMvc mvc;
    private OutboundMessageTestDriver driver;
    private Party a;
    private Party b;

    @BeforeEach
    void setUp() throws Exception {
        MappedGeneralFollowUpGateConfiguration.reset(templates, safety);
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        driver = new OutboundMessageTestDriver(messages, messageAudit, touches,
                new TransactionTemplate(transactionManager), clock);
        a = party("Webhook A");
        b = party("Webhook B");
        verifiedMapping(a);
        verifiedMapping(b);
    }

    /** P3-HOOK-15 and P3-HOOK-06: routed by the verified mapping only, applied once, replays change nothing. */
    @Test
    void aSignedDeliveryMovesOnlyTheMappedTenantsMessageOnceAndAReplayChangesNothing() throws Exception {
        UUID messageA = sent(a, "wamid.A-" + UUID.randomUUID());
        UUID messageB = sent(b, "wamid.B-" + UUID.randomUUID());
        byte[] delivered = statuses(a.phoneNumberId, report(wamid(a, messageA), "delivered", 0, null));
        double duplicatesBefore = dropped("DUPLICATE");

        postSigned(delivered).andExpect(status().isOk()).andExpect(content().bytes(new byte[0]));

        assertThat(message(a, messageA).status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(message(b, messageB).status()).isEqualTo(MessageStatus.SENT);
        assertThat(inboxStates(a)).containsExactly("APPLIED");
        assertThat(deliveryAudits(messageA)).isEqualTo(1);

        postSigned(delivered).andExpect(status().isOk());

        assertThat(message(a, messageA).version()).isEqualTo(6);
        assertThat(inboxStates(a)).containsExactly("APPLIED");
        assertThat(deliveryAudits(messageA)).isEqualTo(1);
        assertThat(dropped("DUPLICATE")).isEqualTo(duplicatesBefore + 1);
        assertThat(inboxStates(b)).isEmpty();

        // The history route shows the provider transition and nothing from the callback itself.
        String history = mvc.perform(get("/api/messages/" + messageA + "/events").with(user(a))
                        .header("X-Tenant-Id", a.tenant.id().value().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[5].type").value("DELIVERY_UPDATED"))
                .andExpect(jsonPath("$.events[5].actorKind").value("PROVIDER"))
                .andExpect(jsonPath("$.events[5].statusTo").value("DELIVERED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(history).doesNotContain(wamid(a, messageA)).doesNotContain(a.phoneNumberId)
                .doesNotContain("recipient");
    }

    /** P3-HOOK-05: tenant A's number naming tenant B's message is parked in A, then dropped; B never changes. */
    @Test
    void anotherTenantsProviderMessageIdIsParkedThenDroppedWithoutTouchingIt() throws Exception {
        UUID messageB = sent(b, "wamid.B-" + UUID.randomUUID());
        double unmatchedBefore = dropped("UNMATCHED_MESSAGE");

        postSigned(statuses(a.phoneNumberId, report(wamid(b, messageB), "delivered", 0, null)))
                .andExpect(status().isOk());

        assertThat(inboxStates(a)).containsExactly("PENDING");
        assertThat(inboxStates(b)).isEmpty();
        endParkWindow(a);
        inbox.sweep();

        assertThat(inboxStates(a)).containsExactly("UNMATCHED");
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM dokene.provider_webhook_events WHERE tenant_id = ? "
                    + "AND provider_message_id IS NOT NULL", a.tenant.id().value())).isZero();
        }
        assertThat(message(b, messageB).status()).isEqualTo(MessageStatus.SENT);
        assertThat(message(b, messageB).version()).isEqualTo(5);
        assertThat(dropped("UNMATCHED_MESSAGE")).isEqualTo(unmatchedBefore + 1);

        // The dedup key is per tenant, so the row left in A never blocks B's own report of the same id and state.
        postSigned(statuses(b.phoneNumberId, report(wamid(b, messageB), "delivered", 0, null)))
                .andExpect(status().isOk());
        assertThat(inboxStates(b)).containsExactly("APPLIED");
        assertThat(message(b, messageB).status()).isEqualTo(MessageStatus.DELIVERED);
    }

    /**
     * P3-HOOK-08 and the issue 210 gate: an unknown number and a number only claimed by an unverified mapping are
     * acknowledged exactly like a normal event and store nothing. Tenant B pre-registering tenant A's number gains
     * nothing: A's verified mapping still routes A's events to A.
     */
    @Test
    void unknownAndUnverifiedNumbersAreAcknowledgedButNeverRouted() throws Exception {
        UUID messageA = sent(a, "wamid.A-" + UUID.randomUUID());
        UUID messageB = sent(b, "wamid.B-" + UUID.randomUUID());
        Party squatter = party("Webhook squatter");
        unverifiedMapping(squatter, a.phoneNumberId);
        Party claimant = party("Webhook claimant");
        String unverifiedOnly = phoneNumberId();
        unverifiedMapping(claimant, unverifiedOnly);
        double unknownBefore = dropped("UNKNOWN_PHONE_NUMBER");

        postSigned(statuses(phoneNumberId(), report(wamid(b, messageB), "read", 0, null)))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[0]));
        postSigned(statuses(unverifiedOnly, report(wamid(b, messageB), "read", 0, null)))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[0]));
        postSigned(statuses(a.phoneNumberId, report(wamid(a, messageA), "read", 0, null)))
                .andExpect(status().isOk());

        assertThat(dropped("UNKNOWN_PHONE_NUMBER")).isEqualTo(unknownBefore + 2);
        assertThat(message(b, messageB).status()).isEqualTo(MessageStatus.SENT);
        assertThat(inboxStates(b)).isEmpty();
        assertThat(inboxStates(squatter)).isEmpty();
        assertThat(inboxStates(claimant)).isEmpty();
        assertThat(message(a, messageA).status()).isEqualTo(MessageStatus.READ);
    }

    /** P3-HOOK-04: a member outside the Meta schema is refused before the tenant is resolved; nothing is stored. */
    @Test
    void aMemberOutsideTheProviderSchemaIsRefusedBeforeAnyTenantIsResolved() throws Exception {
        UUID messageA = sent(a, "wamid.A-" + UUID.randomUUID());
        String smuggled = new String(statuses(a.phoneNumberId, report(wamid(a, messageA), "delivered", 0, null)),
                StandardCharsets.UTF_8).replaceFirst("\\{\"object\"", "{\"tenantId\":\"" + b.tenant.id().value()
                + "\",\"object\"");

        postSigned(smuggled.getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest())
                .andExpect(content().bytes(new byte[0]));
        postSigned("not json".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());

        assertThat(inboxStates(a)).isEmpty();
        assertThat(inboxStates(b)).isEmpty();
        assertThat(message(a, messageA).status()).isEqualTo(MessageStatus.SENT);
    }

    /** P3-HOOK-11, P3-CSRF-04/05 and P3-HOOK-09/10: the route trusts the HMAC only, and only it skips CSRF. */
    @Test
    void onlyTheSignatureAuthenticatesTheRouteWhichAloneSkipsCsrfAndBoundsItsBody() throws Exception {
        UUID messageA = sent(a, "wamid.A-" + UUID.randomUUID());
        byte[] body = statuses(a.phoneNumberId, report(wamid(a, messageA), "delivered", 0, null));

        mvc.perform(post(PATH).with(user(a)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Hub-Signature-256", WebhookSigning.sign(body, "wrong".toCharArray())))
                .andExpect(status().isForbidden());
        byte[] oversized = new byte[65537];
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(oversized)
                        .header("X-Hub-Signature-256", WebhookSigning.sign(oversized, APP_SECRET)))
                .andExpect(status().isForbidden());
        assertThat(message(a, messageA).status()).isEqualTo(MessageStatus.SENT);

        // No CSRF token and no session: the signature alone is enough here, and no session is ever started...
        postSigned(body).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().doesNotExist("Location"));
        assertThat(message(a, messageA).status()).isEqualTo(MessageStatus.DELIVERED);
        // ...while a business route still refuses an unsafe request without one.
        mvc.perform(post("/api/messages").with(user(a)).header("X-Tenant-Id", a.tenant.id().value().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/webhooks/other").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Hub-Signature-256", WebhookSigning.sign(body, APP_SECRET)))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().doesNotExist("Location"));

        mvc.perform(get(PATH).param("hub.mode", "subscribe").param("hub.verify_token", VERIFY_TOKEN)
                        .param("hub.challenge", "challenge-123"))
                .andExpect(status().isOk()).andExpect(content().string("challenge-123"));
        mvc.perform(get(PATH).param("hub.mode", "subscribe").param("hub.verify_token", "wrong")
                        .param("hub.challenge", "challenge-123"))
                .andExpect(status().isForbidden()).andExpect(content().string(""));
    }

    /** A batch in reverse order and a late failure: the state never moves backwards and delivery is never erased. */
    @Test
    void aReorderedMultiStatusBatchAppliesOnlyForwardTransitionsAndALateFailureKeepsTheDelivery() throws Exception {
        UUID first = sent(a, "wamid.A1-" + UUID.randomUUID());
        UUID second = sent(a, "wamid.A2-" + UUID.randomUUID());
        postSigned(statuses(a.phoneNumberId,
                report(wamid(a, first), "read", 20, null),
                report(wamid(a, first), "delivered", 10, null),
                report(wamid(a, second), "delivered", 10, null),
                report(wamid(a, second), "warning", 11, null)))
                .andExpect(status().isOk());
        // The inline limit is 2 in this class: the third tracked status waits for the sweeper.
        assertThat(inboxStates(a)).containsExactlyInAnyOrder("APPLIED", "IGNORED", "PENDING");
        makeDue(a);
        inbox.sweep();
        assertThat(message(a, second).status()).isEqualTo(MessageStatus.DELIVERED);
        postSigned(statuses(a.phoneNumberId, report(wamid(a, second), "failed", 30, 131026)))
                .andExpect(status().isOk());

        assertThat(message(a, first).status()).isEqualTo(MessageStatus.READ);
        OutboundMessage delivered = message(a, second);
        assertThat(delivered.status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(delivered.failureCategory()).isNull();
        assertThat(inboxStates(a)).containsExactlyInAnyOrder("APPLIED", "IGNORED", "APPLIED", "IGNORED");
        assertThat(deliveryAudits(first)).isEqualTo(1);
        assertThat(deliveryAudits(second)).isEqualTo(1);
    }

    /** The T8 race: a report that arrives before the provider id is committed is parked, then applied. */
    @Test
    void aReportArrivingBeforeTheProviderIdIsCommittedIsAppliedOnceItIs() throws Exception {
        UUID messageId = approved(a);
        String providerMessageId = "wamid.RACE-" + UUID.randomUUID();
        inContext(a.owner, () -> driver.requestSend(a.owner, messageId));
        inContext(a.owner, () -> driver.startAttempt(a.owner, messageId));

        postSigned(statuses(a.phoneNumberId, report(providerMessageId, "delivered", 0, null)))
                .andExpect(status().isOk());
        assertThat(inboxStates(a)).containsExactly("PENDING");
        assertThat(message(a, messageId).status()).isEqualTo(MessageStatus.SENDING);

        inContext(a.owner, () -> driver.completeAttempt(a.owner, messageId,
                new ProviderSendResult.Accepted(providerMessageId)));
        makeDue(a);
        inbox.sweep();

        assertThat(inboxStates(a)).containsExactly("APPLIED");
        assertThat(message(a, messageId).status()).isEqualTo(MessageStatus.DELIVERED);
    }

    /** An audit outage while applying never loses acknowledged work: the row stays PENDING and is applied later. */
    @Test
    void anAuditOutageKeepsTheAcknowledgedEventPendingUntilItCanBeApplied() throws Exception {
        UUID messageId = sent(a, "wamid.A-" + UUID.randomUUID());
        doThrow(new AuditPersistenceException()).when(auditRecorder).messageDeliveryUpdated(any(), any(), any());
        try {
            postSigned(statuses(a.phoneNumberId, report(wamid(a, messageId), "delivered", 0, null)))
                    .andExpect(status().isOk());
            assertThat(inboxStates(a)).containsExactly("PENDING");
            assertThat(message(a, messageId).status()).isEqualTo(MessageStatus.SENT);
            assertThat(deliveryAudits(messageId)).isZero();
        } finally {
            doCallRealMethod().when(auditRecorder).messageDeliveryUpdated(any(), any(), any());
        }
        makeDue(a);
        inbox.sweep();

        assertThat(inboxStates(a)).containsExactly("APPLIED");
        assertThat(message(a, messageId).status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(deliveryAudits(messageId)).isEqualTo(1);
    }

    /** A row that keeps failing past the abandon window stops retrying: ABANDONED, with its provider id cleared. */
    @Test
    void aRowThatKeepsFailingIsAbandonedAfterTheAbandonWindow() throws Exception {
        UUID messageId = sent(a, "wamid.A-" + UUID.randomUUID());
        double abandonedBefore = dropped("PROCESSING_ABANDONED");
        doThrow(new AuditPersistenceException()).when(auditRecorder).messageDeliveryUpdated(any(), any(), any());
        try {
            postSigned(statuses(a.phoneNumberId, report(wamid(a, messageId), "delivered", 0, null)))
                    .andExpect(status().isOk());
            assertThat(inboxStates(a)).containsExactly("PENDING");

            try (Connection migration = migrationConnection()) {
                executeUpdate(migration, "UPDATE dokene.provider_webhook_events SET received_at = now() - interval "
                        + "'2 days', next_attempt_at = now() - interval '1 second' WHERE tenant_id = ?",
                        a.tenant.id().value());
            }
            inbox.sweep();
        } finally {
            doCallRealMethod().when(auditRecorder).messageDeliveryUpdated(any(), any(), any());
        }

        assertThat(inboxStates(a)).containsExactly("ABANDONED");
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM dokene.provider_webhook_events WHERE tenant_id = ? "
                    + "AND provider_message_id IS NOT NULL", a.tenant.id().value())).isZero();
        }
        assertThat(message(a, messageId).status()).isEqualTo(MessageStatus.SENT);
        assertThat(dropped("PROCESSING_ABANDONED")).isEqualTo(abandonedBefore + 1);
    }

    /** P3-RLS-04 and retention: rows are invisible without their tenant and purged once past retention. */
    @Test
    void inboxRowsAreTenantIsolatedAndPurgedAfterRetention() throws Exception {
        UUID messageId = sent(a, "wamid.A-" + UUID.randomUUID());
        postSigned(statuses(a.phoneNumberId, report(wamid(a, messageId), "delivered", 0, null)))
                .andExpect(status().isOk());
        assertThat(inboxStates(a)).containsExactly("APPLIED");

        try (Connection runtime = runtimeConnectionWithoutContext()) {
            assertThat(queryInt(runtime, "SELECT count(*) FROM dokene.provider_webhook_events")).isZero();
            assertThat(queryInt(runtime, "SELECT count(*) FROM pg_class WHERE relname = 'provider_webhook_events' "
                    + "AND relrowsecurity AND relforcerowsecurity")).isEqualTo(1);
        }
        assertThat(inContext(b.owner, () -> queryRuntime("SELECT count(*) FROM dokene.provider_webhook_events"
                + " WHERE tenant_id = '" + a.tenant.id().value() + "'"))).isZero();

        try (Connection migration = migrationConnection()) {
            executeUpdate(migration, "UPDATE dokene.provider_webhook_events SET processed_at = now() - interval "
                    + "'31 days' WHERE tenant_id = ?", a.tenant.id().value());
        }
        inbox.sweep();
        assertThat(inboxStates(a)).isEmpty();
    }

    private ResultActions postSigned(byte[] body) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Hub-Signature-256", WebhookSigning.sign(body, APP_SECRET)));
    }

    private static byte[] statuses(String phoneNumberId, String... statuses) {
        return MetaCallbacks.statuses("200000000000001", phoneNumberId, statuses);
    }

    private String report(String providerMessageId, String state, int secondsLater, Integer errorCode) {
        return MetaCallbacks.report(providerMessageId, state, clock.instant().getEpochSecond() + secondsLater,
                errorCode);
    }

    private Party party(String name) throws Exception {
        Instant now = Instant.now();
        Tenant tenant = seedTenant(tenants, contexts, name + " " + UUID.randomUUID(), now);
        IdentityId identity = new IdentityId(UUID.randomUUID());
        TenantContext owner = context(seedMembership(memberships, contexts, tenant.id(), identity, TenantRole.OWNER,
                now));
        Party party = new Party(tenant, identity, owner, phoneNumberId());
        inContext(owner, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        return party;
    }

    /** A message SENT with {@code providerMessageId}, for its own customer so open messages never collide. */
    private UUID sent(Party party, String providerMessageId) throws Exception {
        UUID messageId = approved(party);
        inContext(party.owner, () -> driver.sendAccepted(party.owner, messageId, providerMessageId));
        return messageId;
    }

    private UUID approved(Party party) throws Exception {
        Instant now = Instant.now();
        Customer customer = inContext(party.owner, () -> customers.create("Webhook customer", null,
                List.of(new PhoneInput("8888" + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)),
                        "CR", true))));
        UUID contactId = customer.phones().getFirst().id();
        var policy = inContext(party.owner, () -> contacts.get(customer.id()));
        inContext(party.owner, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
        inContext(party.owner, () -> purchases.record(customer.id(), now.minusSeconds(60), "Anchor purchase",
                "anchor-" + UUID.randomUUID()));
        inContext(party.owner, () -> followUps.configureCustomer(customer.id(), null,
                LocalDate.now(ZoneId.of("UTC")), 0));
        long policyVersion = inContext(party.owner, () -> followUps.evaluateSnapshot(customer.id())).policyVersion();
        UUID messageId = inContext(party.owner, () -> commands.submit(new SubmitMessageCommand(
                customer.id().value(), contactId, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", BODY, "es-419", "MANUAL",
                Long.toString(policyVersion), "submit-" + UUID.randomUUID()))).message().id();
        inContext(party.owner, () -> commands.approve(new ApprovalCommand(messageId, "nota", "1",
                "approve-" + UUID.randomUUID())));
        return messageId;
    }

    private OutboundMessage message(Party party, UUID messageId) throws Exception {
        return inContext(party.owner, () -> messages.findById(party.tenant.id().value(), messageId)).orElseThrow();
    }

    private String wamid(Party party, UUID messageId) throws Exception {
        return message(party, messageId).providerMessageId();
    }

    private static void verifiedMapping(Party party) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection migration = migrationConnection()) {
            executeUpdate(migration, "INSERT INTO dokene.tenant_messaging_integrations (id, tenant_id, provider, "
                    + "phone_number_id, business_account_id, enabled, secret_ref, created_at, updated_at) "
                    + "VALUES (?, ?, 'META_WHATSAPP', ?, '300000000000001', true, 'wa-token', now(), now())", id,
                    party.tenant.id().value(), party.phoneNumberId);
            executeUpdate(migration, "UPDATE dokene.tenant_messaging_integrations SET ownership_verified_at = now() "
                    + "WHERE id = ?", id);
        }
    }

    private static void unverifiedMapping(Party party, String phoneNumberId) throws Exception {
        try (Connection migration = migrationConnection()) {
            executeUpdate(migration, "INSERT INTO dokene.tenant_messaging_integrations (id, tenant_id, provider, "
                    + "phone_number_id, business_account_id, enabled, secret_ref, created_at, updated_at) "
                    + "VALUES (?, ?, 'META_WHATSAPP', ?, '300000000000002', true, 'wa-token', now(), now())",
                    UUID.randomUUID(), party.tenant.id().value(), phoneNumberId);
        }
    }

    private static List<String> inboxStates(Party party) throws Exception {
        try (Connection migration = migrationConnection();
                var statement = migration.prepareStatement("SELECT state FROM dokene.provider_webhook_events "
                        + "WHERE tenant_id = ? ORDER BY received_at, id")) {
            statement.setObject(1, party.tenant.id().value());
            try (var result = statement.executeQuery()) {
                List<String> states = new ArrayList<>();
                while (result.next()) {
                    states.add(result.getString(1));
                }
                return states;
            }
        }
    }

    /** Moves the tenant's PENDING rows past the park window, as if fifteen minutes had passed. */
    private static void endParkWindow(Party party) throws Exception {
        try (Connection migration = migrationConnection()) {
            executeUpdate(migration, "UPDATE dokene.provider_webhook_events SET received_at = now() - interval "
                    + "'20 minutes', next_attempt_at = now() - interval '1 second' WHERE tenant_id = ? "
                    + "AND state = 'PENDING'", party.tenant.id().value());
        }
    }

    private static void makeDue(Party party) throws Exception {
        try (Connection migration = migrationConnection()) {
            executeUpdate(migration, "UPDATE dokene.provider_webhook_events SET next_attempt_at = now() - interval "
                    + "'1 second' WHERE tenant_id = ? AND state = 'PENDING'", party.tenant.id().value());
        }
    }

    private static int deliveryAudits(UUID messageId) throws Exception {
        try (Connection migration = migrationConnection()) {
            return queryInt(migration, "SELECT count(*) FROM dokene.audit_events WHERE target_id = ? "
                    + "AND event_type = 'MESSAGE_DELIVERY_UPDATED'", messageId);
        }
    }

    private double dropped(String reason) {
        var counter = meters.find("dokene.webhook.dropped").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private int queryRuntime(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static String phoneNumberId() {
        return "1" + String.format("%014d", ThreadLocalRandom.current().nextLong(100_000_000_000_000L));
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }

    private static RequestPostProcessor user(Party party) {
        return authentication(new UsernamePasswordAuthenticationToken(new TestPrincipal(party.identity), "test",
                List.of()));
    }

    private record Party(Tenant tenant, IdentityId identity, TenantContext owner, String phoneNumberId) {
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
}
