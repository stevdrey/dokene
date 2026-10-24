// ABOUTME: English outbound messages end to end over the HTTP API (issue 268): Meta's zero-parameter hello_world
// ABOUTME: template in en_US is mapped, reviewed with its fixed text, approved, sent once and tracked to delivery.
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
 * Nothing on the messaging path is stubbed: the registry-backed template gate, the outbound safety control, local
 * secret resolution, the Graph adapter and the webhook verifier all run. Meta is a loopback server that lists
 * {@code hello_world} the way the Graph API does (static header, body and footer) and counts every send, so each
 * refusal can prove it reached the provider zero times. All values are synthetic.
 */
@SpringBootTest(properties = {"dokene.ai.provider=fake", "dokene.messaging.outbound.enabled=true",
        "dokene.messaging.english.enabled=true",
        "dokene.messaging.send-timeout=3s", "dokene.integration.whatsapp.graph.timeout=2s"})
class EnglishTemplateSendJourneyIntegrationTest {
    private static final char[] APP_SECRET = "SYNTHETIC-APP-SECRET-NOT-REAL-0268".toCharArray();
    private static final String WABA_ID = "200000000000268";
    private static final String TOKEN_REF = "wa-token";
    private static final String HELLO_HEADER = "Hello World";
    private static final String HELLO_BODY = "Welcome and congratulations!! This message demonstrates your ability "
            + "to send a WhatsApp message notification from the Cloud API, hosted by Meta. Thank you for taking the "
            + "time to test with us.";
    private static final String HELLO_FOOTER = "WhatsApp Business Platform sample message";
    private static final List<TenantId> TENANT_IDS = IntStream.range(0, 10).mapToObj(i -> TenantId.random()).toList();
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
                "SYNTHETIC-VERIFY-TOKEN-0268");
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
        GRAPH.helloBody = HELLO_BODY;
        GRAPH.helloFooter = HELLO_FOOTER;
        workspace = workspace();
    }

    @Test
    void helloWorldInEnglishIsReviewedWithItsFixedTextApprovedSentOnceAndTrackedToDelivery() throws Exception {
        Customer customer = dueCustomer("John Smith");
        mapHelloWorld();
        // With English enabled, compose is offered both locales, default first.
        JsonNode locales = body(perform(get("/api/messaging/locales"), workspace.operator));
        assertThat(locales.path("locales").toString()).isEqualTo("[\"es-419\",\"en-US\"]");
        assertThat(locales.path("defaultLocale").asString()).isEqualTo("es-419");

        // The registry shows the fixed text read from Meta with the approval evidence.
        JsonNode slot = findIntent(body(perform(get("/api/templates"), workspace.owner)), "GENERAL_FOLLOW_UP");
        assertThat(slot.path("language").asString()).isEqualTo("en_US");
        assertThat(slot.path("content").path("headerText").asString()).isEqualTo(HELLO_HEADER);
        assertThat(slot.path("content").path("bodyText").asString()).isEqualTo(HELLO_BODY);
        assertThat(slot.path("content").path("footerText").asString()).isEqualTo(HELLO_FOOTER);

        Message message = submit(customer, "en-US", HELLO_BODY, "MANUAL");
        JsonNode review = message.read(this);
        assertThat(review.path("locale").asString()).isEqualTo("en-US");
        assertThat(review.path("status").asString()).isEqualTo("PENDING_APPROVAL");
        JsonNode preview = review.path("template");
        assertThat(preview.path("providerTemplateName").asString()).isEqualTo("hello_world");
        assertThat(preview.path("language").asString()).isEqualTo("en_US");
        assertThat(preview.path("headerText").asString()).isEqualTo(HELLO_HEADER);
        assertThat(preview.path("bodyText").asString()).isEqualTo(HELLO_BODY);
        assertThat(preview.path("footerText").asString()).isEqualTo(HELLO_FOOTER);
        assertThat(preview.path("parameterNames")).isEmpty();
        assertThat(GRAPH.sends(workspace)).isZero();

        // A client older than this release approves without a revision; it never showed an English preview.
        assertProblem(approve(workspace.operator, message, null), 400, "INVALID_INPUT");
        assertThat(message.read(this).path("status").asString()).isEqualTo("PENDING_APPROVAL");
        approve(workspace.operator, message);
        assertThat(GRAPH.sends(workspace)).isZero();

        String key = "send-" + UUID.randomUUID();
        String approvedEtag = message.etag(this);
        MockHttpServletResponse sent = send(workspace.operator, message, key, approvedEtag);
        assertThat(sent.getStatus()).as(sent.getContentAsString()).isEqualTo(201);
        JsonNode accepted = body(sent);
        assertThat(accepted.path("status").asString()).isEqualTo("SENT");
        String wamid = accepted.path("providerMessageId").asString();

        // Exactly one Graph request: Meta's en_US code and no components, since a fixed template takes no values.
        assertThat(GRAPH.sends(workspace)).isEqualTo(1);
        JsonNode payload = json.readTree(GRAPH.lastSend(workspace).body());
        assertThat(payload.path("to").asString()).isEqualTo(customer.phone);
        assertThat(payload.path("template").path("name").asString()).isEqualTo("hello_world");
        assertThat(payload.path("template").path("language").path("code").asString()).isEqualTo("en_US");
        assertThat(payload.path("template").has("components")).isFalse();

        // A double click replays the result; neither it nor a new key reaches Meta again.
        MockHttpServletResponse replay = send(workspace.operator, message, key, approvedEtag);
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(body(replay).path("providerMessageId").asString()).isEqualTo(wamid);
        assertProblem(send(workspace.operator, message, "again-" + UUID.randomUUID()), 409, "INVALID_TRANSITION");
        assertThat(GRAPH.sends(workspace)).isEqualTo(1);

        // Meta's signed callback for this wamid is applied to this message: provider acceptance was SENT, the
        // callback is what makes it DELIVERED.
        assertThat(postSigned(statuses(workspace.phoneNumberId, report(wamid, "delivered"))).getStatus())
                .isEqualTo(200);
        JsonNode detail = message.read(this);
        assertThat(detail.path("status").asString()).isEqualTo("DELIVERED");
        assertThat(detail.path("providerMessageId").asString()).isEqualTo(wamid);
        // Once dispatched, the current mapping may not be what was sent, so no preview is shown.
        assertThat(detail.path("template").isNull()).isTrue();
        JsonNode listed = body(perform(get("/api/messages").param("customerId", customer.id.toString()),
                workspace.viewer)).path("items").get(0);
        assertThat(listed.path("locale").asString()).isEqualTo("en-US");

        // Another tenant cannot see the English message at all.
        Workspace mine = workspace;
        workspace = workspace();
        assertProblem(perform(get("/api/messages/" + message.id), workspace.owner), 404, "NOT_FOUND");
        workspace = mine;
    }

    @Test
    void mismatchedLanguageAlteredTextUnsupportedLocaleAndEnglishAiOriginFailClosed() throws Exception {
        Customer customer = dueCustomer("Mary Jones");
        mapHelloWorld();

        // A zero-parameter template sends only its fixed text: any other body would be approved but never sent.
        assertProblem(submitResponse(customer, "en-US", "Hi Mary, how was your purchase?", "MANUAL"), 409,
                "BODY_REJECTED");
        // Spanish content cannot travel under an English template, and no locale outside the allowlist is accepted.
        assertProblem(submitResponse(customer, "es-419", HELLO_BODY, "MANUAL"), 409, "TEMPLATE_NOT_MAPPED");
        assertProblem(submitResponse(customer, "en-GB", HELLO_BODY, "MANUAL"), 400, "INVALID_INPUT");
        assertProblem(submitResponse(customer, "fr-FR", HELLO_BODY, "MANUAL"), 400, "INVALID_INPUT");
        // English AI drafts are unavailable; an English message cannot claim AI origin.
        assertProblem(submitResponse(customer, "en-US", HELLO_BODY, "AI_DRAFT"), 400, "INVALID_INPUT");

        // An en template that is not exactly en_US does not carry an en-US message either.
        remap("hello_world", "en", "[]");
        assertProblem(submitResponse(customer, "en-US", HELLO_BODY, "MANUAL"), 409, "TEMPLATE_NOT_MAPPED");
        assertThat(GRAPH.sends(workspace)).isZero();
    }

    @Test
    void consentChangesAndAChangedProviderTextAfterApprovalReachMetaZeroTimes() throws Exception {
        Customer customer = dueCustomer("Peter Brown");
        mapHelloWorld();
        Message message = submit(customer, "en-US", HELLO_BODY, "MANUAL");
        approve(workspace.operator, message);

        consent(customer, "REVOKED");
        assertProblem(send(workspace.operator, message, "revoked-" + UUID.randomUUID()), 409, "NO_CONTACT_CONSENT");
        consent(customer, "GRANTED");
        assertProblem(send(workspace.operator, message, "regranted-" + UUID.randomUUID()), 409,
                "APPROVAL_INVALIDATED");
        // The approval no longer covers the message, so its detail shows no template as awaiting dispatch.
        assertThat(message.read(this).path("template").isNull()).isTrue();
        assertThat(GRAPH.sends(workspace)).isZero();

        // A template refreshed between review and approval cannot be approved under the preview that was reviewed.
        Customer reviewedLate = dueCustomer("Laura Green");
        Message pending = submit(reviewedLate, "en-US", HELLO_BODY, "MANUAL");
        String reviewed = pending.read(this).path("template").path("revision").asString();
        GRAPH.helloFooter = "WhatsApp Business Platform updated sample";
        refreshTemplate("GENERAL_FOLLOW_UP", findIntent(body(perform(get("/api/templates"), workspace.owner)),
                "GENERAL_FOLLOW_UP").path("version").asLong());
        assertProblem(approve(workspace.operator, pending, reviewed), 409, "STALE_VERSION");
        assertThat(pending.read(this).path("status").asString()).isEqualTo("PENDING_APPROVAL");

        // Meta's text changes and the mapping is refreshed: what was approved is no longer what would be sent.
        Customer other = dueCustomer("Linda White");
        Message approved = submit(other, "en-US", HELLO_BODY, "MANUAL");
        approve(workspace.operator, approved);
        assertThat(approved.read(this).path("template").path("footerText").asString())
                .isEqualTo("WhatsApp Business Platform updated sample");
        // A footer edited after approval still renders, but nobody approved it: the detail shows no template.
        GRAPH.helloFooter = "WhatsApp Business Platform edited after approval";
        refreshTemplate("GENERAL_FOLLOW_UP", findIntent(body(perform(get("/api/templates"), workspace.owner)),
                "GENERAL_FOLLOW_UP").path("version").asLong());
        assertThat(approved.read(this).path("template").isNull()).isTrue();
        assertProblem(send(workspace.operator, approved, "footer-" + UUID.randomUUID()), 409, "APPROVAL_INVALIDATED");
        GRAPH.helloBody = HELLO_BODY + " Edited.";
        JsonNode slot = findIntent(body(perform(get("/api/templates"), workspace.owner)), "GENERAL_FOLLOW_UP");
        refreshTemplate("GENERAL_FOLLOW_UP", slot.path("version").asLong());
        assertProblem(send(workspace.operator, approved, "edited-" + UUID.randomUUID()), 409, "BODY_REJECTED");
        assertThat(approved.read(this).path("status").asString()).isEqualTo("APPROVED");
        assertThat(GRAPH.sends(workspace)).isZero();
    }

    @Test
    void spanishMessagesKeepTheirTemplateAndBodyParameter() throws Exception {
        Customer customer = dueCustomer("Ana Pérez");
        MockHttpServletResponse created = perform(put("/api/templates/GENERAL_FOLLOW_UP").header("If-None-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).content(templateJson("general_follow_up_v1", "es",
                        "[\"customer_name\",\"message_body\"]")), workspace.owner);
        assertThat(created.getStatus()).as(created.getContentAsString()).isIn(200, 201);
        refreshTemplate("GENERAL_FOLLOW_UP", body(created).path("version").asLong());

        String text = "Hola, queríamos saber cómo le ha ido con su compra.";
        Message message = submit(customer, "es-419", text, "MANUAL");
        JsonNode preview = message.read(this).path("template");
        assertThat(preview.path("language").asString()).isEqualTo("es");
        assertThat(preview.path("bodyText").asString()).isEqualTo("Hola {{1}}, {{2}}");
        // Expand first: a client older than this release still approves a Spanish message without a revision.
        MockHttpServletResponse legacy = approve(workspace.operator, message, null);
        assertThat(legacy.getStatus()).as(legacy.getContentAsString()).isEqualTo(201);
        assertThat(send(workspace.operator, message, "send-" + UUID.randomUUID()).getStatus()).isEqualTo(201);

        JsonNode payload = json.readTree(GRAPH.lastSend(workspace).body());
        assertThat(payload.path("template").path("language").path("code").asString()).isEqualTo("es");
        assertThat(payload.path("template").path("components").toString()).contains("Ana Pérez").contains(text);
    }

    // --- journey steps over the public API --------------------------------------------------------------------

    private Workspace workspace() throws Exception {
        int next = NEXT_TENANT.getAndIncrement();
        assertThat(next).as("pre-registered tenant ids").isLessThan(TENANT_IDS.size());
        Instant now = Instant.now();
        Tenant tenant = TenantTestSupport.saveTenant(tenants, contexts,
                Tenant.create(TENANT_IDS.get(next), "English " + UUID.randomUUID(), now));
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
        MockHttpServletResponse current = perform(get("/api/integrations/whatsapp"), owner);
        MockHttpServletResponse outbound = perform(put("/api/integrations/whatsapp/outbound")
                .header("If-Match", current.getHeader("ETag")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"), owner);
        assertThat(outbound.getStatus()).as(outbound.getContentAsString()).isEqualTo(200);

        MockHttpServletResponse policy = perform(get("/api/follow-up-policy"), owner);
        MockHttpServletResponse cadence = perform(put("/api/follow-up-policy").header("If-Match", policy.getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"cadenceDays\":30,\"timeZone\":\"UTC\"}"), owner);
        assertThat(cadence.getStatus()).as(cadence.getContentAsString()).isEqualTo(200);
        return created;
    }

    /** A customer with WhatsApp consent and an explicit next follow-up date of today. */
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

        String base = "/api/customers/" + customer.id + "/follow-up-policy";
        MockHttpServletResponse policy = perform(get(base), workspace.operator);
        MockHttpServletResponse due = perform(put(base).header("If-Match", policy.getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"cadenceDays\":30,\"explicitNextDate\":\""
                        + LocalDate.now(ZoneId.of("UTC")) + "\"}"), workspace.operator);
        assertThat(due.getStatus()).as(due.getContentAsString()).isEqualTo(200);
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

    /** Maps the intent to Meta's zero-parameter hello_world in en_US and verifies it against the loopback catalogue. */
    private void mapHelloWorld() throws Exception {
        MockHttpServletResponse created = perform(put("/api/templates/GENERAL_FOLLOW_UP").header("If-None-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).content(templateJson("hello_world", "en_US", "[]")),
                workspace.owner);
        assertThat(created.getStatus()).as(created.getContentAsString()).isIn(200, 201);
        refreshTemplate("GENERAL_FOLLOW_UP", body(created).path("version").asLong());
    }

    private void remap(String name, String language, String parameters) throws Exception {
        JsonNode slot = findIntent(body(perform(get("/api/templates"), workspace.owner)), "GENERAL_FOLLOW_UP");
        MockHttpServletResponse edited = perform(put("/api/templates/GENERAL_FOLLOW_UP")
                .header("If-Match", "\"" + slot.path("version").asLong() + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(templateJson(name, language, parameters)),
                workspace.owner);
        assertThat(edited.getStatus()).as(edited.getContentAsString()).isEqualTo(200);
        refreshTemplate("GENERAL_FOLLOW_UP", body(edited).path("version").asLong());
    }

    private void refreshTemplate(String intent, long version) throws Exception {
        MockHttpServletResponse refreshed = perform(post("/api/templates/" + intent + "/approval-refresh")
                .header("If-Match", "\"" + version + "\""), workspace.owner);
        assertThat(refreshed.getStatus()).as(refreshed.getContentAsString()).isEqualTo(200);
        assertThat(body(refreshed).path("sendable").asBoolean()).isTrue();
    }

    private MockHttpServletResponse submitResponse(Customer customer, String locale, String text, String origin)
            throws Exception {
        return perform(post("/api/customers/" + customer.id + "/messages")
                .header("Idempotency-Key", "submit-" + UUID.randomUUID())
                .header("If-Match", "\"" + customer.policyVersion() + "\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactId\":\"" + customer.contactId + "\",\"action\":\"GENERAL_CHECK_IN\","
                        + "\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":" + json.writeValueAsString(text)
                        + ",\"locale\":\"" + locale + "\",\"origin\":\"" + origin + "\"}"), workspace.operator);
    }

    private Message submit(Customer customer, String locale, String text, String origin) throws Exception {
        MockHttpServletResponse response = submitResponse(customer, locale, text, origin);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return new Message(UUID.fromString(body(response).path("messageId").asString()));
    }

    /** Approves the template preview the reviewer reads now (issue 268). */
    private void approve(IdentityId who, Message message) throws Exception {
        MockHttpServletResponse response = approve(who, message, message.read(this).path("template").path("revision")
                .asString());
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        assertThat(body(response).path("status").asString()).isEqualTo("APPROVED");
    }

    private MockHttpServletResponse approve(IdentityId who, Message message, String templateRevision)
            throws Exception {
        return perform(post("/api/messages/" + message.id + "/approvals")
                .header("Idempotency-Key", "approve-" + UUID.randomUUID()).header("If-Match", message.etag(this))
                .contentType(MediaType.APPLICATION_JSON).content(templateRevision == null
                        ? "{\"decision\":\"APPROVE\"}"
                        : "{\"decision\":\"APPROVE\",\"templateRevision\":\"" + templateRevision + "\"}"), who);
    }

    private MockHttpServletResponse send(IdentityId who, Message message, String key) throws Exception {
        return send(who, message, key, message.etag(this));
    }

    private MockHttpServletResponse send(IdentityId who, Message message, String key, String ifMatch)
            throws Exception {
        return perform(post("/api/messages/" + message.id + "/sends").header("Idempotency-Key", key)
                .header("If-Match", ifMatch), who);
    }

    private MockHttpServletResponse postSigned(byte[] callback) throws Exception {
        return mvc.perform(post("/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON).content(callback)
                .header("X-Hub-Signature-256", WebhookSigning.sign(callback, APP_SECRET))).andReturn().getResponse();
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

    private static String templateJson(String providerName, String language, String parameterNames) {
        return "{\"providerTemplateName\":\"" + providerName + "\",\"language\":\"" + language
                + "\",\"parameterNames\":" + parameterNames + ",\"enabled\":true}";
    }

    private static byte[] statuses(String phoneNumberId, String status) {
        return ("{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"" + WABA_ID + "\",\"changes\":[{"
                + "\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":{"
                + "\"display_phone_number\":\"50600000000\",\"phone_number_id\":\"" + phoneNumberId + "\"},"
                + "\"statuses\":[" + status + "]}}]}]}").getBytes(StandardCharsets.UTF_8);
    }

    private static String report(String wamid, String state) {
        return "{\"id\":\"" + wamid + "\",\"status\":\"" + state + "\",\"timestamp\":\""
                + Instant.now().getEpochSecond() + "\",\"recipient_id\":\"50600000001\"}";
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
        JsonNode read(EnglishTemplateSendJourneyIntegrationTest test) throws Exception {
            return test.body(test.perform(get("/api/messages/" + id), test.workspace.viewer));
        }

        String etag(EnglishTemplateSendJourneyIntegrationTest test) throws Exception {
            return test.perform(get("/api/messages/" + id), test.workspace.viewer).getHeader("ETag");
        }
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }

    /**
     * Loopback Meta: proves ownership of every registered phone number, lists {@code hello_world} with Meta's static
     * components (its body text can be changed to simulate an edit on Meta's side) and any other template as an
     * approved two-parameter Spanish one, and accepts sends while counting them per phone number id.
     */
    static final class GraphStub {
        record Request(String phoneNumberId, String body) {
        }

        final HttpServer server;
        final Set<String> ownedPhoneNumbers = ConcurrentHashMap.newKeySet();
        final List<Request> sends = new CopyOnWriteArrayList<>();
        volatile String helloBody = HELLO_BODY;
        volatile String helloFooter = HELLO_FOOTER;

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
                    String components = "hello_world".equals(query.get("name"))
                            ? "{\"type\":\"HEADER\",\"format\":\"TEXT\",\"text\":\"" + HELLO_HEADER + "\"},"
                                    + "{\"type\":\"BODY\",\"text\":\"" + helloBody + "\"},"
                                    + "{\"type\":\"FOOTER\",\"text\":\"" + helloFooter + "\"}"
                            : "{\"type\":\"BODY\",\"text\":\"Hola {{1}}, {{2}}\"}";
                    reply(exchange, 200, "{\"data\":[{\"name\":\"" + query.get("name") + "\",\"language\":\""
                            + query.get("language") + "\",\"status\":\"APPROVED\",\"components\":[" + components
                            + "]}]}");
                } else if (path.endsWith("/messages") && "POST".equals(exchange.getRequestMethod())) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    sends.add(new Request(path.substring(0, path.indexOf('/')), body));
                    reply(exchange, 200, "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":"
                            + "\"+50600000001\",\"wa_id\":\"50600000001\"}],\"messages\":[{\"id\":"
                            + "\"wamid.SYNTHETIC-" + UUID.randomUUID() + "\",\"message_status\":\"accepted\"}]}");
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
