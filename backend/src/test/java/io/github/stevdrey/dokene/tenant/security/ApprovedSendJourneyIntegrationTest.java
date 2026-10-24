// ABOUTME: The Phase 3 approved-send journey end to end over the HTTP API (issue 140): customer, consent, purchase,
// ABOUTME: AI draft, explicit approval, manual send through the real Graph adapter and a signed delivery callback.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.integration.testsupport.WebhookSigning;
import io.github.stevdrey.dokene.tenant.TenantTestSupport;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nothing on the messaging path is stubbed: the registry-backed template gate, the real outbound safety control and
 * kill switches, local secret resolution, the Graph adapter and the webhook verifier all run. Meta itself is a
 * loopback server that answers like the synthetic fixtures in {@code contracts/whatsapp} and counts every request, so
 * each refusal can prove it reached the provider zero times. Only the AI provider is the deterministic fake.
 */
@SpringBootTest(properties = {"dokene.ai.provider=fake", "dokene.messaging.outbound.enabled=true",
        "dokene.messaging.send-timeout=3s", "dokene.integration.whatsapp.graph.timeout=2s"})
class ApprovedSendJourneyIntegrationTest {
    /** The synthetic key of contracts/whatsapp/graph-webhook-events.json; never a real Meta app secret. */
    private static final char[] APP_SECRET = "SYNTHETIC-APP-SECRET-NOT-REAL-0001".toCharArray();
    private static final String WABA_ID = "200000000000001";
    private static final String TOKEN_REF = "wa-token";
    /** Tenant secrets are keyed by tenant id at startup, so every test draws a pre-registered tenant id. */
    private static final List<TenantId> TENANT_IDS = IntStream.range(0, 12).mapToObj(i -> TenantId.random()).toList();
    private static final AtomicInteger NEXT_TENANT = new AtomicInteger();
    private static final GraphStub GRAPH = GraphStub.start();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("dokene.integration.whatsapp.graph.base-url", GRAPH::baseUrl);
        registry.add("dokene.integration.secrets.source", () -> "local");
        int index = 0;
        index = secret(registry, index, "deployment", null, "webhook-app-secret", "APP_SECRET",
                new String(APP_SECRET));
        index = secret(registry, index, "deployment", null, "webhook-verify-token", "WEBHOOK_VERIFY_TOKEN",
                "SYNTHETIC-VERIFY-TOKEN-0001");
        for (TenantId tenant : TENANT_IDS) {
            index = secret(registry, index, "tenant", tenant, TOKEN_REF, "WHATSAPP_ACCESS_TOKEN", token(tenant));
        }
        registry.add("dokene.integration.whatsapp.app-secret-ref", () -> "webhook-app-secret");
        registry.add("dokene.integration.whatsapp.verify-token-ref", () -> "webhook-verify-token");
    }

    @AfterAll
    static void stopGraph() {
        GRAPH.server.stop(0);
    }

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;
    private Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        GRAPH.sendReply = GraphStub.Reply.ACCEPTED;
        workspace = workspace();
    }

    @Test
    void anAiDraftIsSentOnceOnlyAfterExplicitApprovalAndTrackedToDelivery() throws Exception {
        Customer customer = dueCustomer("Valentina Morales");
        JsonNode draft = draft(customer);
        mapTemplate(draft.path("templateIntent").asString());

        Message message = submit(customer, draft);
        assertThat(message.read(this).path("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(message.read(this).path("origin").asString()).isEqualTo("AI_DRAFT");
        // A generated draft is only a pending message: nothing reached Meta before a person approved it.
        assertThat(GRAPH.sends(workspace)).isZero();
        assertProblem(send(workspace.operator, message, "early-" + UUID.randomUUID()), 409, "INVALID_TRANSITION");
        assertThat(GRAPH.sends(workspace)).isZero();

        approve(workspace.operator, message);
        assertThat(GRAPH.sends(workspace)).isZero();

        String key = "send-" + UUID.randomUUID();
        String approvedEtag = message.etag(this);
        MockHttpServletResponse sent = send(workspace.operator, message, key, approvedEtag);
        assertThat(sent.getStatus()).isEqualTo(201);
        JsonNode accepted = body(sent);
        assertThat(accepted.path("status").asString()).isEqualTo("SENT");
        assertThat(accepted.path("outcomeUnknown").asBoolean()).isFalse();
        assertThat(accepted.path("attempts").get(0).path("outcome").asString()).isEqualTo("ACCEPTED");
        String wamid = accepted.path("providerMessageId").asString();
        assertThat(wamid).startsWith("wamid.SYNTHETIC-");

        // Exactly one Graph request, carrying the tenant's own credential and the approved recipient and body.
        assertThat(GRAPH.sends(workspace)).isEqualTo(1);
        GraphStub.Request request = GRAPH.lastSend(workspace);
        assertThat(request.authorization()).isEqualTo("Bearer " + token(workspace.tenant.id()));
        assertThat(request.body()).contains("\"to\":\"" + customer.phone + "\"")
                .contains(draft.path("body").asString());

        // A double click repeats the same key and precondition and replays the result; a later click with a new key
        // is refused. Neither reaches Meta.
        MockHttpServletResponse replay = send(workspace.operator, message, key, approvedEtag);
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(body(replay).path("providerMessageId").asString()).isEqualTo(wamid);
        assertProblem(send(workspace.operator, message, "again-" + UUID.randomUUID()), 409, "INVALID_TRANSITION");
        assertThat(GRAPH.sends(workspace)).isEqualTo(1);

        // Meta's signed callback moves the message to DELIVERED; the same bytes again change nothing.
        byte[] delivered = statuses(workspace.phoneNumberId, report(wamid, "delivered", null));
        assertThat(postSigned(delivered).getStatus()).isEqualTo(200);
        assertThat(postSigned(delivered).getStatus()).isEqualTo(200);
        JsonNode detail = message.read(this);
        assertThat(detail.path("status").asString()).isEqualTo("DELIVERED");
        assertThat(detail.path("deliveredAt").isNull()).isFalse();
        assertThat(eventTypes(message)).containsExactly("SUBMITTED", "APPROVED", "SEND_REQUESTED",
                "SEND_ATTEMPT_STARTED", "SENT", "DELIVERY_UPDATED");
        assertThat(GRAPH.sends(workspace)).isEqualTo(1);
    }

    @Test
    void aFailedDeliveryReportEndsInFailedWithASafeCategory() throws Exception {
        Customer customer = dueCustomer("Ana Pérez");
        mapTemplate("GENERAL_FOLLOW_UP");
        Message message = approvedManualMessage(customer);
        String wamid = body(send(workspace.operator, message, "send-" + UUID.randomUUID()))
                .path("providerMessageId").asString();

        assertThat(postSigned(statuses(workspace.phoneNumberId, report(wamid, "failed", 131026))).getStatus())
                .isEqualTo(200);

        JsonNode detail = message.read(this);
        assertThat(detail.path("status").asString()).isEqualTo("FAILED");
        assertThat(detail.path("failureCategory").asString()).isEqualTo("INVALID_RECIPIENT");
        // The provider's error text is never echoed to the business UI.
        assertThat(detail.toString()).doesNotContain("CANARY-TOKEN", "CANARY-PHONE");
    }

    @Test
    void anUncertainProviderAnswerIsKeptUncertainAndNeverResent() throws Exception {
        Customer customer = dueCustomer("Luis Mora");
        mapTemplate("GENERAL_FOLLOW_UP");
        Message message = approvedManualMessage(customer);
        GRAPH.sendReply = GraphStub.Reply.SERVER_ERROR;

        JsonNode unknown = body(send(workspace.operator, message, "send-" + UUID.randomUUID()));

        assertThat(unknown.path("status").asString()).isEqualTo("SENDING");
        assertThat(unknown.path("outcomeUnknown").asBoolean()).isTrue();
        assertThat(eventTypes(message)).contains("SEND_OUTCOME_UNKNOWN");
        GRAPH.sendReply = GraphStub.Reply.ACCEPTED;
        assertProblem(send(workspace.operator, message, "retry-" + UUID.randomUUID()), 409, "INVALID_TRANSITION");
        assertThat(GRAPH.sends(workspace)).isEqualTo(1);
    }

    @Test
    void refusalsAfterApprovalReachMetaZeroTimes() throws Exception {
        Customer customer = dueCustomer("Marta Solís");
        mapTemplate("GENERAL_FOLLOW_UP");
        Message message = approvedManualMessage(customer);

        // Authorization: a viewer can read the message but never send it.
        assertProblem(send(workspace.viewer, message, "viewer-" + UUID.randomUUID()), 403, "FORBIDDEN");
        // Emergency disable: the tenant switch off refuses the send at the last moment.
        outbound(false);
        assertProblem(send(workspace.operator, message, "off-" + UUID.randomUUID()), 409,
                "OUTBOUND_DISABLED_FOR_TENANT");
        outbound(true);
        // Opt-out: consent revoked after approval.
        consent(customer, "REVOKED");
        assertProblem(send(workspace.operator, message, "revoked-" + UUID.randomUUID()), 409, "NO_CONTACT_CONSENT");
        // Re-granting starts a new consent run that the earlier approval does not cover.
        consent(customer, "GRANTED");
        assertProblem(send(workspace.operator, message, "regranted-" + UUID.randomUUID()), 409,
                "APPROVAL_INVALIDATED");

        assertThat(message.read(this).path("status").asString()).isEqualTo("APPROVED");
        assertThat(message.read(this).path("attempts")).isEmpty();
        assertThat(GRAPH.sends(workspace)).isZero();
    }

    @Test
    void aTemplateEditAfterApprovalIsDriftAndAStaleDraftIsNotSubmitted() throws Exception {
        Customer customer = dueCustomer("Carlos Vega");
        mapTemplate("GENERAL_FOLLOW_UP");
        Message message = approvedManualMessage(customer);

        // Re-mapping the intent after approval: the approval no longer binds what Meta would receive.
        MockHttpServletResponse template = perform(get("/api/templates"), workspace.owner);
        JsonNode slot = findIntent(body(template), "GENERAL_FOLLOW_UP");
        MockHttpServletResponse edited = perform(put("/api/templates/GENERAL_FOLLOW_UP")
                .header("If-Match", "\"" + slot.path("version").asLong() + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(templateJson("general_follow_up_v2")),
                workspace.owner);
        assertThat(edited.getStatus()).isEqualTo(200);
        refreshTemplate("GENERAL_FOLLOW_UP", body(edited).path("version").asLong());
        assertProblem(send(workspace.operator, message, "drift-" + UUID.randomUUID()), 409, "APPROVAL_INVALIDATED");
        assertThat(GRAPH.sends(workspace)).isZero();

        // A draft read against an older follow-up policy version cannot be submitted.
        Customer other = dueCustomer("Elena Ruiz");
        String submitted = submitJson(other, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "Hola Elena, ¿cómo le va?",
                "MANUAL");
        MockHttpServletResponse stale = perform(post("/api/customers/" + other.id + "/messages")
                .header("Idempotency-Key", "stale-" + UUID.randomUUID())
                .header("If-Match", "\"" + (other.policyVersion() - 1) + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(submitted), workspace.operator);
        assertProblem(stale, 409, "STALE_VERSION");
    }

    // --- journey steps over the public API --------------------------------------------------------------------

    /** A tenant with an owner, an operator, a viewer, a proven integration and the tenant outbound switch on. */
    private Workspace workspace() throws Exception {
        int next = NEXT_TENANT.getAndIncrement();
        assertThat(next).as("pre-registered tenant ids").isLessThan(TENANT_IDS.size());
        Instant now = Instant.now();
        Tenant tenant = TenantTestSupport.saveTenant(tenants, contexts,
                Tenant.create(TENANT_IDS.get(next), "Journey " + UUID.randomUUID(), now));
        IdentityId owner = member(tenant, TenantRole.OWNER, now);
        IdentityId operator = member(tenant, TenantRole.OPERATOR, now);
        IdentityId viewer = member(tenant, TenantRole.VIEWER, now);
        String phoneNumberId = "1" + String.format("%014d", ThreadLocalRandom.current().nextLong(100_000_000_000_000L));
        Workspace created = new Workspace(tenant, owner, operator, viewer, phoneNumberId);
        workspace = created;
        GRAPH.ownedPhoneNumbers.add(phoneNumberId);

        MockHttpServletResponse configured = perform(put("/api/integrations/whatsapp").header("If-None-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumberId\":\"" + phoneNumberId
                        + "\",\"businessAccountId\":\"" + WABA_ID + "\",\"displayPhoneNumber\":\"+50600000000\","
                        + "\"enabled\":true,\"secretRef\":\"" + TOKEN_REF + "\"}"), owner);
        assertThat(configured.getStatus()).as(configured.getContentAsString()).isEqualTo(200);
        MockHttpServletResponse proven = perform(post("/api/integrations/whatsapp/ownership-verifications")
                .header("If-Match", configured.getHeader("ETag")), owner);
        assertThat(proven.getStatus()).as(proven.getContentAsString()).isEqualTo(200);
        assertThat(body(proven).path("webhookVerified").asBoolean()).isTrue();
        // The tenant switch is off by default; flipping it on is a deliberate admin step.
        assertThat(body(proven).path("outboundEnabled").asBoolean()).isFalse();
        outbound(true);

        MockHttpServletResponse policy = perform(get("/api/follow-up-policy"), owner);
        MockHttpServletResponse cadence = perform(put("/api/follow-up-policy").header("If-Match", policy.getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"cadenceDays\":30,\"timeZone\":\"UTC\"}"), owner);
        assertThat(cadence.getStatus()).as(cadence.getContentAsString()).isEqualTo(200);
        return created;
    }

    /** A customer with WhatsApp consent, a recorded purchase and an explicit next follow-up date of today. */
    private Customer dueCustomer(String name) throws Exception {
        String local = "8888" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
        MockHttpServletResponse created = perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"" + name + "\",\"phones\":[{\"number\":\"" + local
                        + "\",\"region\":\"CR\",\"primary\":true}]}"), workspace.operator);
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        JsonNode node = body(created);
        Customer customer = new Customer(UUID.fromString(node.path("id").asString()),
                UUID.fromString(node.path("phones").get(0).path("id").asString()), "+506" + local);
        consent(customer, "GRANTED");

        Instant purchasedAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        MockHttpServletResponse purchase = perform(post("/api/customers/" + customer.id + "/purchases")
                .header("Idempotency-Key", "purchase-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchasedAt\":\"" + purchasedAt + "\",\"description\":\"Kit de café de especialidad\"}"),
                workspace.operator);
        assertThat(purchase.getStatus()).as(purchase.getContentAsString()).isEqualTo(201);

        String base = "/api/customers/" + customer.id + "/follow-up-policy";
        MockHttpServletResponse policy = perform(get(base), workspace.operator);
        MockHttpServletResponse due = perform(put(base).header("If-Match", policy.getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"cadenceDays\":30,\"explicitNextDate\":\""
                        + LocalDate.now(ZoneId.of("UTC")) + "\"}"), workspace.operator);
        assertThat(due.getStatus()).as(due.getContentAsString()).isEqualTo(200);

        JsonNode queue = body(perform(get("/api/follow-up-queue"), workspace.operator));
        assertThat(queue.path("items").valueStream().map(item -> item.path("customerId").asString()))
                .contains(customer.id.toString());
        return customer;
    }

    private void consent(Customer customer, String status) throws Exception {
        String base = "/api/customers/" + customer.id;
        MockHttpServletResponse policy = perform(get(base + "/contact-policy"), workspace.operator);
        MockHttpServletResponse changed = perform(put(base + "/contacts/" + customer.contactId + "/consents/WHATSAPP")
                .header("If-Match", policy.getHeader("ETag")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\",\"source\":\"CUSTOMER_WRITTEN\"}"), workspace.operator);
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
    }

    /** The AI draft for the due customer; its ETag is the policy version the submit must match. */
    private JsonNode draft(Customer customer) throws Exception {
        MockHttpServletResponse response = perform(post("/api/customers/" + customer.id + "/draft"),
                workspace.operator);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode node = body(response);
        assertThat(node.path("status").asString()).isEqualTo("AVAILABLE");
        customer.draftEtag = response.getHeader("ETag");
        return node.path("draft");
    }

    private Message submit(Customer customer, JsonNode draft) throws Exception {
        MockHttpServletResponse response = perform(post("/api/customers/" + customer.id + "/messages")
                .header("Idempotency-Key", "submit-" + UUID.randomUUID()).header("If-Match", customer.draftEtag)
                .contentType(MediaType.APPLICATION_JSON).content(submitJson(customer, draft.path("action").asString(),
                        draft.path("templateIntent").asString(), draft.path("body").asString(), "AI_DRAFT")),
                workspace.operator);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return new Message(UUID.fromString(body(response).path("messageId").asString()));
    }

    private Message approvedManualMessage(Customer customer) throws Exception {
        MockHttpServletResponse response = perform(post("/api/customers/" + customer.id + "/messages")
                .header("Idempotency-Key", "submit-" + UUID.randomUUID())
                .header("If-Match", "\"" + customer.policyVersion() + "\"").contentType(MediaType.APPLICATION_JSON)
                .content(submitJson(customer, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP",
                        "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.", "MANUAL")),
                workspace.operator);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        Message message = new Message(UUID.fromString(body(response).path("messageId").asString()));
        approve(workspace.operator, message);
        return message;
    }

    private void approve(IdentityId who, Message message) throws Exception {
        MockHttpServletResponse response = perform(post("/api/messages/" + message.id + "/approvals")
                .header("Idempotency-Key", "approve-" + UUID.randomUUID()).header("If-Match", message.etag(this))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"), who);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        assertThat(body(response).path("status").asString()).isEqualTo("APPROVED");
    }

    private MockHttpServletResponse send(IdentityId who, Message message, String key) throws Exception {
        return send(who, message, key, message.etag(this));
    }

    private MockHttpServletResponse send(IdentityId who, Message message, String key, String ifMatch)
            throws Exception {
        return perform(post("/api/messages/" + message.id + "/sends").header("Idempotency-Key", key)
                .header("If-Match", ifMatch), who);
    }

    private void outbound(boolean enabled) throws Exception {
        MockHttpServletResponse current = perform(get("/api/integrations/whatsapp"), workspace.owner);
        MockHttpServletResponse flipped = perform(put("/api/integrations/whatsapp/outbound")
                .header("If-Match", current.getHeader("ETag")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"), workspace.owner);
        assertThat(flipped.getStatus()).as(flipped.getContentAsString()).isEqualTo(200);
    }

    /** Maps the intent to an approved two-parameter template, verified against the loopback Graph catalogue. */
    private void mapTemplate(String intent) throws Exception {
        MockHttpServletResponse created = perform(put("/api/templates/" + intent).header("If-None-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).content(templateJson(intent.toLowerCase() + "_v1")),
                workspace.owner);
        assertThat(created.getStatus()).as(created.getContentAsString()).isIn(200, 201);
        refreshTemplate(intent, body(created).path("version").asLong());
    }

    private void refreshTemplate(String intent, long version) throws Exception {
        MockHttpServletResponse refreshed = perform(post("/api/templates/" + intent + "/approval-refresh")
                .header("If-Match", "\"" + version + "\""), workspace.owner);
        assertThat(refreshed.getStatus()).as(refreshed.getContentAsString()).isEqualTo(200);
        assertThat(body(refreshed).path("sendable").asBoolean()).isTrue();
    }

    private MockHttpServletResponse postSigned(byte[] callback) throws Exception {
        return mvc.perform(post("/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON).content(callback)
                .header("X-Hub-Signature-256", WebhookSigning.sign(callback, APP_SECRET))).andReturn().getResponse();
    }

    private List<String> eventTypes(Message message) throws Exception {
        JsonNode events = body(perform(get("/api/messages/" + message.id + "/events"), workspace.viewer));
        return events.path("events").valueStream().map(event -> event.path("type").asString()).toList();
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, IdentityId who) throws Exception {
        return mvc.perform(request.with(csrf()).header("X-Tenant-Id", workspace.tenant.id().value().toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(new TestPrincipal(who), "test",
                        List.of())))).andReturn().getResponse();
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(body(response).path("code").asString()).isEqualTo(code);
    }

    private static JsonNode findIntent(JsonNode list, String intent) {
        return list.path("items").valueStream().filter(item -> intent.equals(item.path("templateIntent").asString()))
                .findFirst().orElseThrow();
    }

    private IdentityId member(Tenant tenant, TenantRole role, Instant now) {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), identity, role, now);
        return identity;
    }

    private static String templateJson(String providerName) {
        return "{\"providerTemplateName\":\"" + providerName + "\",\"language\":\"es\","
                + "\"parameterNames\":[\"customer_name\",\"message_body\"],\"enabled\":true}";
    }

    private static String submitJson(Customer customer, String action, String intent, String text, String origin) {
        return "{\"contactId\":\"" + customer.contactId + "\",\"action\":\"" + action + "\",\"templateIntent\":\""
                + intent + "\",\"body\":" + JsonMapper.builder().build().writeValueAsString(text)
                + ",\"locale\":\"es-419\",\"origin\":\"" + origin + "\"}";
    }

    private static byte[] statuses(String phoneNumberId, String status) {
        return ("{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"" + WABA_ID + "\",\"changes\":[{"
                + "\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":{"
                + "\"display_phone_number\":\"50600000000\",\"phone_number_id\":\"" + phoneNumberId + "\"},"
                + "\"statuses\":[" + status + "]}}]}]}").getBytes(StandardCharsets.UTF_8);
    }

    private static String report(String wamid, String state, Integer errorCode) {
        String errors = errorCode == null ? "" : ",\"errors\":[{\"code\":" + errorCode
                + ",\"title\":\"CANARY-TOKEN\",\"message\":\"CANARY-PHONE\"}]";
        return "{\"id\":\"" + wamid + "\",\"status\":\"" + state + "\",\"timestamp\":\""
                + Instant.now().getEpochSecond() + "\",\"recipient_id\":\"50600000001\"" + errors + "}";
    }

    private static String token(TenantId tenant) {
        return "SYNTHETIC-NOT-A-REAL-TOKEN-" + tenant.value();
    }

    private static int secret(DynamicPropertyRegistry registry, int index, String scope, TenantId tenant, String ref,
            String purpose, String value) {
        String prefix = "dokene.integration.secrets.local.entries[" + index + "].";
        registry.add(prefix + "scope", () -> scope);
        if (tenant != null) {
            registry.add(prefix + "tenant-id", () -> tenant.value().toString());
        }
        registry.add(prefix + "ref", () -> ref);
        registry.add(prefix + "purpose", () -> purpose);
        registry.add(prefix + "value", () -> value);
        return index + 1;
    }

    private record Workspace(Tenant tenant, IdentityId owner, IdentityId operator, IdentityId viewer,
            String phoneNumberId) {
    }

    private final class Customer {
        final UUID id;
        final UUID contactId;
        final String phone;
        String draftEtag;

        Customer(UUID id, UUID contactId, String phone) {
            this.id = id;
            this.contactId = contactId;
            this.phone = phone;
        }

        long policyVersion() throws Exception {
            String etag = perform(get("/api/customers/" + id + "/follow-up-policy"), workspace.operator)
                    .getHeader("ETag");
            return Long.parseLong(etag.substring(1, etag.length() - 1));
        }
    }

    private record Message(UUID id) {
        JsonNode read(ApprovedSendJourneyIntegrationTest test) throws Exception {
            return test.body(test.perform(get("/api/messages/" + id), test.workspace.viewer));
        }

        String etag(ApprovedSendJourneyIntegrationTest test) throws Exception {
            return test.perform(get("/api/messages/" + id), test.workspace.viewer).getHeader("ETag");
        }
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }

    /**
     * Loopback Meta: proves ownership of every phone number a test registered, reports every looked-up template as
     * APPROVED with two body parameters, and answers sends with a scripted reply while counting them per phone id.
     */
    static final class GraphStub {
        enum Reply { ACCEPTED, SERVER_ERROR }

        record Request(String phoneNumberId, String authorization, String body) {
        }

        final HttpServer server;
        final Set<String> ownedPhoneNumbers = ConcurrentHashMap.newKeySet();
        final List<Request> sends = new CopyOnWriteArrayList<>();
        volatile Reply sendReply = Reply.ACCEPTED;

        private GraphStub(HttpServer server) {
            this.server = server;
        }

        static GraphStub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                GraphStub stub = new GraphStub(server);
                server.createContext("/v23.0/", stub::handle);
                server.start();
                return stub;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v23.0";
        }

        int sends(Workspace workspace) {
            return (int) sends.stream().filter(send -> send.phoneNumberId().equals(workspace.phoneNumberId())).count();
        }

        Request lastSend(Workspace workspace) {
            return sends.stream().filter(send -> send.phoneNumberId().equals(workspace.phoneNumberId()))
                    .reduce((first, second) -> second).orElseThrow();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath().substring("/v23.0/".length());
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            try (exchange) {
                if (path.endsWith("/phone_numbers")) {
                    StringBuilder data = new StringBuilder();
                    for (String id : ownedPhoneNumbers) {
                        data.append(data.isEmpty() ? "" : ",").append("{\"id\":\"").append(id).append("\"}");
                    }
                    reply(exchange, 200, "{\"data\":[" + data + "]}");
                } else if (path.endsWith("/message_templates")) {
                    reply(exchange, 200, "{\"data\":[{\"name\":\"" + query.get("name") + "\",\"language\":\""
                            + query.get("language") + "\",\"status\":\"APPROVED\",\"components\":[{\"type\":\"BODY\","
                            + "\"text\":\"Hola {{1}}, {{2}}\"}]}]}");
                } else if (path.endsWith("/messages") && "POST".equals(exchange.getRequestMethod())) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    sends.add(new Request(path.substring(0, path.indexOf('/')),
                            exchange.getRequestHeaders().getFirst("Authorization"), body));
                    if (sendReply == Reply.SERVER_ERROR) {
                        reply(exchange, 500, "{\"error\":{\"message\":\"An unknown error occurred\",\"type\":"
                                + "\"OAuthException\",\"code\":1,\"fbtrace_id\":\"SYNTHETIC-TRACE\"}}");
                    } else {
                        reply(exchange, 200, "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":"
                                + "\"+50600000001\",\"wa_id\":\"50600000001\"}],\"messages\":[{\"id\":"
                                + "\"wamid.SYNTHETIC-" + UUID.randomUUID() + "\",\"message_status\":\"accepted\"}]}");
                    }
                } else {
                    reply(exchange, 404, "{\"error\":{\"message\":\"Unsupported\",\"code\":100}}");
                }
            }
        }

        private static Map<String, String> query(String raw) {
            Map<String, String> values = new ConcurrentHashMap<>();
            if (raw != null) {
                for (String pair : raw.split("&")) {
                    int equals = pair.indexOf('=');
                    if (equals > 0) {
                        values.put(pair.substring(0, equals),
                                URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                    }
                }
            }
            return values;
        }

        private static void reply(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
