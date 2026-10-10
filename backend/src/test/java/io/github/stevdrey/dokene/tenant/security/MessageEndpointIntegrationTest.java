// ABOUTME: Drives spec 0002 over the real HTTP stack against Postgres: replay, problem bodies, denials, RLS, paging.
// ABOUTME: Every assertion maps to an AC of spec 0002; seeding mirrors OutboundMessagingIntegrationTest.
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
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.messaging.application.MessageAuditPort;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageTestDriver;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.AuthorizationDecision;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.slf4j.LoggerFactory;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"dokene.ai.provider=fake",
        "dokene.messaging.template-gate.stub.enabled-intents=GENERAL_FOLLOW_UP"})
class MessageEndpointIntegrationTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";
    private static final String NOTE = "nota privada del aprobador";

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
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboundMessageRepository messages;
    @Autowired MessageAuditPort messageAudit;
    @Autowired FollowUpTouchRecorder touches;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;
    @MockitoSpyBean AuditRecorder auditRecorder;
    @MockitoSpyBean TenantAuthorizationService authorization;

    private Tenant tenantA;
    private Tenant tenantB;
    private IdentityId ownerA;
    private IdentityId viewerA;
    private IdentityId ownerB;
    private TenantContext ownerAContext;
    private TenantContext ownerBContext;
    private Seeded seeded;
    private OutboundMessageTestDriver driver;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger messagingLogger = (Logger) LoggerFactory.getLogger("io.github.stevdrey.dokene.messaging");

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, "Messages A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, "Messages B " + UUID.randomUUID(), now);
        ownerA = new IdentityId(UUID.randomUUID());
        viewerA = new IdentityId(UUID.randomUUID());
        ownerB = new IdentityId(UUID.randomUUID());
        ownerAContext = context(seedMembership(memberships, contexts, tenantA.id(), ownerA, TenantRole.OWNER, now));
        seedMembership(memberships, contexts, tenantA.id(), viewerA, TenantRole.VIEWER, now);
        ownerBContext = context(seedMembership(memberships, contexts, tenantB.id(), ownerB, TenantRole.OWNER, now));
        inContext(ownerAContext, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        seeded = seedCustomer("Cliente principal");
        driver = new OutboundMessageTestDriver(messages, messageAudit, touches,
                new TransactionTemplate(transactionManager), clock);
        logs.start();
        messagingLogger.addAppender(logs);
    }

    /** AC-9 across the whole suite: nothing the messaging package logs carries a body, a phone or a note. */
    @AfterEach
    void detachLogsAndProveTheyAreClean() {
        messagingLogger.detachAppender(logs);
        logs.stop();
        for (ILoggingEvent entry : logs.list) {
            assertThat(entry.getFormattedMessage()).doesNotContain(BODY).doesNotContain(NOTE)
                    .doesNotContain(seeded.phone());
        }
    }

    @Test
    void submitAnswersOnceAndReplaysWithoutASecondRowOrAuditEvent() throws Exception {
        String key = "submit-" + UUID.randomUUID();
        MvcResult first = mvc.perform(submit(seeded, key, BODY)).andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.customerDisplayName").value("Cliente principal"))
                .andExpect(jsonPath("$.body").value(BODY))
                .andExpect(jsonPath("$.contactRemoved").value(false))
                .andExpect(jsonPath("$.lastAttempt").value((Object) null))
                .andExpect(jsonPath("$.sourcePolicyVersion").value(seeded.policyVersion()))
                .andReturn();
        UUID messageId = UUID.fromString(json.readTree(first.getResponse().getContentAsString())
                .get("messageId").asString());
        assertThat(first.getResponse().getHeader("Location")).isEqualTo("/api/messages/" + messageId);

        MvcResult replay = mvc.perform(submit(seeded, key, BODY)).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\"")).andExpect(header().doesNotExist("Location"))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(rowCount("outbound_messages", messageId)).isEqualTo(1);
        assertThat(rowCount("outbound_message_events", messageId)).isEqualTo(1);
        assertThat(auditRows(messageId, "MESSAGE_SUBMITTED")).isEqualTo(1);

        mvc.perform(submit(seeded, key, BODY + " otra cosa")).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(submit(seeded, "submit-" + UUID.randomUUID(), BODY)).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "MESSAGE_ALREADY_OPEN"));
        mvc.perform(submit(seeded, "submit-" + UUID.randomUUID(), BODY, "\"" + (seeded.policyVersion() + 5) + "\""))
                .andExpect(status().isConflict()).andExpect(problem(409, "Conflict", "STALE_VERSION"));
    }

    @Test
    void viewerIsDeniedBeforeBindingWithOneAuditedDenialAndCanStillRead() throws Exception {
        mvc.perform(post("/api/customers/{id}/messages", seeded.customer().id().value()).with(user(viewerA))
                        .with(csrf()).header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isForbidden()).andExpect(problem(403, "Forbidden", "FORBIDDEN"));
        verify(auditRecorder, times(1)).authorizationDenied(eq(TenantPermission.MESSAGE_DRAFT), any());
        verify(auditRecorder, times(1)).authorizationDenied(any(), any());

        mvc.perform(get("/api/messages").with(user(viewerA)).header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());

        UUID messageId = submitted(seeded);
        mvc.perform(post("/api/messages/{id}/approvals", messageId).with(user(viewerA)).with(csrf())
                        .header("X-Tenant-Id", tenantA.id().value()).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden()).andExpect(problem(403, "Forbidden", "FORBIDDEN"));
        mvc.perform(post("/api/messages/{id}/cancellations", messageId).with(user(viewerA)).with(csrf())
                        .header("X-Tenant-Id", tenantA.id().value()).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "k-" + UUID.randomUUID()))
                .andExpect(status().isForbidden()).andExpect(problem(403, "Forbidden", "FORBIDDEN"));
        // One audited denial per denied request: approvals and cancellations both audit the first listed permission.
        verify(auditRecorder, times(2)).authorizationDenied(eq(TenantPermission.MESSAGE_APPROVE), any());
        verify(auditRecorder, times(3)).authorizationDenied(any(), any());
    }

    @Test
    void approvesCancelsAndRefusesStaleOrInvalidTransitionsWithTheEventsToProveIt() throws Exception {
        UUID messageId = submitted(seeded);

        mvc.perform(approval(messageId, "\"0\"", "APPROVE", null)).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "STALE_VERSION"));
        String approveKey = "approve-" + UUID.randomUUID();
        mvc.perform(approval(messageId, "\"1\"", "APPROVE", NOTE, approveKey)).andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"2\"")).andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approval.decision").value("APPROVE"))
                .andExpect(jsonPath("$.approval.note").value(NOTE))
                .andExpect(jsonPath("$.approval.decidedByMembershipId").isNotEmpty());
        mvc.perform(approval(messageId, "\"1\"", "APPROVE", NOTE, approveKey)).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\"")).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(approval(messageId, "\"2\"", "APPROVE", null)).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "INVALID_TRANSITION"));
        mvc.perform(approval(messageId, "\"2\"", "REJECT", null)).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "INVALID_TRANSITION"));

        mvc.perform(cancellation(messageId, "\"2\"", "{\"note\":\"cambio de número\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellation.statusFrom").value("APPROVED"))
                .andExpect(jsonPath("$.cancellation.note").value("cambio de número"));
        mvc.perform(cancellation(messageId, "\"3\"", null)).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "INVALID_TRANSITION"));
        assertThat(logs.list).isNotEmpty();

        MvcResult events = mvc.perform(get("/api/messages/{id}/events", messageId).with(user(ownerA))
                        .header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(3))
                .andExpect(jsonPath("$.events[0].sequenceNumber").value(1))
                .andExpect(jsonPath("$.events[0].eventType").value("SUBMITTED"))
                .andExpect(jsonPath("$.events[0].statusFrom").value((Object) null))
                .andExpect(jsonPath("$.events[0].statusTo").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.events[0].actorKind").value("MEMBER"))
                .andExpect(jsonPath("$.events[0].actorMembershipId").isNotEmpty())
                .andExpect(jsonPath("$.events[0].applied").value(true))
                .andExpect(jsonPath("$.events[2].sequenceNumber").value(3))
                .andExpect(jsonPath("$.events[2].statusFrom").value("APPROVED"))
                .andExpect(jsonPath("$.events[2].statusTo").value("CANCELLED"))
                .andReturn();
        String eventsBody = events.getResponse().getContentAsString();
        assertThat(eventsBody).doesNotContain(NOTE).doesNotContain("cambio de número").doesNotContain(BODY)
                .doesNotContain(seeded.phone());

        // A newly cancelled message frees the one open message slot: the next submit succeeds.
        mvc.perform(submit(seeded, "submit-" + UUID.randomUUID(), BODY)).andExpect(status().isCreated());
    }

    @Test
    void listsNewestFirstWithCursorsFiltersAndCustomerNamesAndNeverTheBody() throws Exception {
        Seeded second = seedCustomer("Segundo cliente");
        Seeded third = seedCustomer("Tercer cliente");
        UUID firstId = submitted(seeded);
        UUID secondId = submitted(second);
        UUID thirdId = submitted(third);

        MvcResult page1 = mvc.perform(list().param("limit", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].messageId").value(thirdId.toString()))
                .andExpect(jsonPath("$.items[0].customerDisplayName").value("Tercer cliente"))
                .andExpect(jsonPath("$.items[0].body").doesNotExist())
                .andExpect(jsonPath("$.items[0].contactRemoved").value(false))
                .andExpect(jsonPath("$.items[1].messageId").value(secondId.toString()))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty()).andReturn();
        String cursor = json.readTree(page1.getResponse().getContentAsString()).get("nextCursor").asString();
        mvc.perform(list().param("limit", "2").param("cursor", cursor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].messageId").value(firstId.toString()))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));

        mvc.perform(list().param("status", "PENDING_APPROVAL").param("status", "PENDING_APPROVAL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(list().param("status", "CANCELLED").param("status", "FAILED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(list().param("customerId", second.customer().id().value().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].messageId").value(secondId.toString()));
        mvc.perform(list().param("customerId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(list().param("status", "")).andExpect(status().isBadRequest())
                .andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
    }

    @Test
    void derivesContactRemovedOnceThePhoneIsReplaced() throws Exception {
        UUID messageId = submitted(seeded);
        Customer current = inContext(ownerAContext, () -> customers.get(seeded.customer().id()));
        inContext(ownerAContext, () -> customers.update(seeded.customer().id(), current.version(),
                current.displayName(), current.notes(), List.of(new PhoneInput(freshNumber(), "CR", true))));

        mvc.perform(get("/api/messages/{id}", messageId).with(user(ownerA)).header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contactRemoved").value(true))
                .andExpect(jsonPath("$.recipientPhone").value(seeded.phone()));
        mvc.perform(list()).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].contactRemoved").value(true));
    }

    @Test
    void foreignTenantSeesNothing() throws Exception {
        UUID messageId = submitted(seeded);
        String tenant = tenantB.id().value().toString();
        mvc.perform(get("/api/messages/{id}", messageId).with(user(ownerB)).header("X-Tenant-Id", tenant))
                .andExpect(status().isNotFound()).andExpect(problem(404, "Not Found", "NOT_FOUND"));
        mvc.perform(get("/api/messages/{id}/events", messageId).with(user(ownerB)).header("X-Tenant-Id", tenant))
                .andExpect(status().isNotFound()).andExpect(problem(404, "Not Found", "NOT_FOUND"));
        mvc.perform(post("/api/messages/{id}/approvals", messageId).with(user(ownerB)).with(csrf())
                        .header("X-Tenant-Id", tenant).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNotFound()).andExpect(problem(404, "Not Found", "NOT_FOUND"));
        mvc.perform(post("/api/messages/{id}/cancellations", messageId).with(user(ownerB)).with(csrf())
                        .header("X-Tenant-Id", tenant).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "k-" + UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(problem(404, "Not Found", "NOT_FOUND"));
        mvc.perform(get("/api/messages").with(user(ownerB)).header("X-Tenant-Id", tenant))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        assertThat(rowCount("outbound_message_approvals", messageId)).isZero();

        // The joined read paths under tenant B's own runtime context see nothing of tenant A.
        assertThat(rowCountAs(ownerBContext, "outbound_messages", messageId)).isZero();
        assertThat(rowCountAs(ownerBContext, "outbound_message_events", messageId)).isZero();
        assertThat(inContext(ownerBContext, () -> jdbc.queryForObject(
                "SELECT count(*) FROM dokene.customers WHERE id = ?", Integer.class, seeded.customer().id().value())))
                .isZero();
        mvc.perform(get("/api/messages").with(user(ownerB)).header("X-Tenant-Id", tenant)
                        .param("customerId", seeded.customer().id().value().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void foreignTenantReplayingTheSameIdempotencyKeyNeverSeesTenantAsMessage() throws Exception {
        String key = "submit-" + UUID.randomUUID();
        MvcResult first = mvc.perform(submit(seeded, key, BODY)).andExpect(status().isCreated()).andReturn();
        String messageId = json.readTree(first.getResponse().getContentAsString()).get("messageId").asString();

        MvcResult replayFromB = mvc.perform(post("/api/customers/{id}/messages", seeded.customer().id().value())
                        .with(user(ownerB)).with(csrf()).header("X-Tenant-Id", tenantB.id().value())
                        .header("If-Match", "\"" + seeded.policyVersion() + "\"").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(submitBody(seeded, BODY)))
                .andExpect(status().isNotFound()).andExpect(problem(404, "Not Found", "NOT_FOUND")).andReturn();
        assertThat(replayFromB.getResponse().getContentAsString()).doesNotContain(messageId);
        assertThat(rowCount("outbound_messages", UUID.fromString(messageId))).isEqualTo(1);
    }

    @Test
    void sentMessagesRefuseCancelAndApproveAsInvalidTransitions() throws Exception {
        UUID messageId = submitted(seeded);
        mvc.perform(approval(messageId, "\"1\"", "APPROVE", null)).andExpect(status().isCreated());
        inContext(ownerAContext, () -> driver.sendAccepted(ownerAContext, messageId, "wamid." + UUID.randomUUID()));

        MvcResult detail = mvc.perform(get("/api/messages/{id}", messageId).with(user(ownerA))
                        .header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.lastAttempt.attemptNumber").value(1))
                .andExpect(jsonPath("$.attempts.length()").value(1)).andReturn();
        String etag = detail.getResponse().getHeader("ETag");
        mvc.perform(cancellation(messageId, etag, null)).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "INVALID_TRANSITION"));
        mvc.perform(approval(messageId, etag, "REJECT", null)).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "INVALID_TRANSITION"));
        assertThat(rowCount("outbound_message_cancellations", messageId)).isZero();
    }

    /** No shipped role holds MESSAGE_DRAFT without MESSAGE_APPROVE, so the state specific refusal is forced. */
    @Test
    void cancelOfAnApprovedMessageIsRefusedWhenOnlyDraftIsHeld() throws Exception {
        UUID messageId = submitted(seeded);
        mvc.perform(approval(messageId, "\"1\"", "APPROVE", null)).andExpect(status().isCreated());

        doReturn(false).when(authorization).hasPermission(TenantPermission.MESSAGE_APPROVE);
        doReturn(AuthorizationDecision.deny("test")).when(authorization).evaluate(TenantPermission.MESSAGE_APPROVE);
        doThrow(new TenantAccessDeniedException("test")).when(authorization)
                .requirePermission(TenantPermission.MESSAGE_APPROVE);

        mvc.perform(cancellation(messageId, "\"2\"", null)).andExpect(status().isForbidden())
                .andExpect(problem(403, "Forbidden", "FORBIDDEN"));
        assertThat(rowCount("outbound_message_cancellations", messageId)).isZero();
        mvc.perform(get("/api/messages/{id}", messageId).with(user(ownerA)).header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private UUID submitted(Seeded target) throws Exception {
        MvcResult result = mvc.perform(submit(target, "submit-" + UUID.randomUUID(), BODY))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("messageId").asString());
    }

    private MockHttpServletRequestBuilder submit(Seeded target, String key, String body) {
        return submit(target, key, body, "\"" + target.policyVersion() + "\"");
    }

    private MockHttpServletRequestBuilder submit(Seeded target, String key, String body, String ifMatch) {
        return post("/api/customers/{id}/messages", target.customer().id().value()).with(user(ownerA)).with(csrf())
                .header("X-Tenant-Id", tenantA.id().value()).header("If-Match", ifMatch)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(target, body));
    }

    private static String submitBody(Seeded target, String body) {
        return """
                {"contactId":"%s","action":"GENERAL_CHECK_IN","templateIntent":"GENERAL_FOLLOW_UP",
                 "body":"%s","locale":"es-419","origin":"MANUAL"}
                """.formatted(target.contactId(), body);
    }

    private MockHttpServletRequestBuilder approval(UUID messageId, String ifMatch, String decision, String note) {
        return approval(messageId, ifMatch, decision, note, "approve-" + UUID.randomUUID());
    }

    private MockHttpServletRequestBuilder approval(UUID messageId, String ifMatch, String decision, String note,
            String key) {
        String body = note == null ? "{\"decision\":\"" + decision + "\"}"
                : "{\"decision\":\"" + decision + "\",\"note\":\"" + note + "\"}";
        return post("/api/messages/{id}/approvals", messageId).with(user(ownerA)).with(csrf())
                .header("X-Tenant-Id", tenantA.id().value()).header("If-Match", ifMatch)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder cancellation(UUID messageId, String ifMatch, String body) {
        var builder = post("/api/messages/{id}/cancellations", messageId).with(user(ownerA)).with(csrf())
                .header("X-Tenant-Id", tenantA.id().value()).header("If-Match", ifMatch)
                .header("Idempotency-Key", "cancel-" + UUID.randomUUID());
        return body == null ? builder : builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder list() {
        return get("/api/messages").with(user(ownerA)).header("X-Tenant-Id", tenantA.id().value());
    }

    private static ResultMatcher problem(int status, String title, String code) {
        return result -> {
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            content().json("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\"}"
                    .formatted(title, status, code), JsonCompareMode.STRICT).match(result);
        };
    }

    private Seeded seedCustomer(String displayName) throws Exception {
        Instant now = Instant.now();
        String number = freshNumber();
        Customer customer = inContext(ownerAContext, () -> customers.create(displayName, null,
                List.of(new PhoneInput(number, "CR", true))));
        UUID contactId = customer.phones().getFirst().id();
        var policy = inContext(ownerAContext, () -> contacts.get(customer.id()));
        inContext(ownerAContext, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
        inContext(ownerAContext, () -> purchases.record(customer.id(), now.minusSeconds(60), "Anchor purchase",
                "anchor-" + UUID.randomUUID()));
        inContext(ownerAContext, () -> followUps.configureCustomer(customer.id(), null,
                LocalDate.now(ZoneId.of("UTC")), 0));
        var snapshot = inContext(ownerAContext, () -> followUps.evaluateSnapshot(customer.id()));
        assertThat(snapshot.evaluation().status()).isEqualTo(FollowUpStatus.DUE);
        return new Seeded(customer, contactId, "+506" + number, snapshot.policyVersion());
    }

    private static String freshNumber() {
        return "8888" + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000);
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
        return auditExecution.callWithCorrelation(UUID.randomUUID(), () -> contexts.callWithContext(context, operation::call));
    }

    private RequestPostProcessor user(IdentityId identityId) {
        var principal = new TestPrincipal(identityId);
        return authentication(new UsernamePasswordAuthenticationToken(principal, "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }

    private record Seeded(Customer customer, UUID contactId, String phone, long policyVersion) { }
}
