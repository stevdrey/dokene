// ABOUTME: Proves the /api/messages review and approval routes over HTTP against Postgres (issue 136): role matrix,
// ABOUTME: cross-tenant ids, strict bodies, CSRF, ETags, replay, consent revocation, approval binding and audit rollback.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.messaging.application.ApprovalBindingAssembler;
import io.github.stevdrey.dokene.messaging.application.MessageAuditPort;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubSafetyControl;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubTemplateResolver;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "dokene.ai.provider=fake")
@Import(MappedGeneralFollowUpGateConfiguration.class)
class MessageApiIntegrationTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";
    private static final String PROBLEM = "application/problem+json";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
    }

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired CustomerService customers;
    @Autowired ContactPolicyService contacts;
    @Autowired ContactPolicyRepository contactPolicies;
    @Autowired PurchaseService purchases;
    @Autowired FollowUpService followUps;
    @Autowired OutboundMessageRepository messages;
    @Autowired ApprovalBindingAssembler bindings;
    @Autowired StubTemplateResolver templates;
    @Autowired StubSafetyControl safety;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean MessageAuditPort audit;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;
    private Tenant tenantA;
    private Tenant tenantB;
    private Member ownerA;
    private Member operatorA;
    private Member viewerA;
    private Member ownerB;
    private Customer customer;
    private UUID contactId;
    private long policyVersion;
    private String lastLocation;

    private record Member(IdentityId identity, TenantContext context) {
    }

    @BeforeEach
    void setUp() throws Exception {
        MappedGeneralFollowUpGateConfiguration.reset(templates, safety);
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, contexts, "Message API A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, contexts, "Message API B " + UUID.randomUUID(), now);
        ownerA = member(tenantA, TenantRole.OWNER);
        operatorA = member(tenantA, TenantRole.OPERATOR);
        viewerA = member(tenantA, TenantRole.VIEWER);
        ownerB = member(tenantB, TenantRole.OWNER);
        customer = inContext(ownerA, () -> customers.create("Ana Pérez", null,
                List.of(new PhoneInput("8888" + String.format("%04d",
                        Math.abs(UUID.randomUUID().hashCode()) % 10000), "CR", true))));
        contactId = customer.phones().getFirst().id();
        setConsent(ConsentStatus.GRANTED);
        inContext(ownerA, () -> purchases.record(customer.id(), now.minusSeconds(60), "Anchor purchase",
                "anchor-" + UUID.randomUUID()));
        inContext(ownerA, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        inContext(ownerA, () -> followUps.configureCustomer(customer.id(), null, LocalDate.now(ZoneId.of("UTC")), 0));
        policyVersion = inContext(ownerA, () -> followUps.evaluateSnapshot(customer.id())).policyVersion();
    }

    @AfterEach
    void resetStubs() {
        MappedGeneralFollowUpGateConfiguration.reset(templates, safety);
    }

    @Test
    void submitsReadsAndApprovesWithoutAnyProviderSideEffect() throws Exception {
        String submitKey = "submit-" + UUID.randomUUID();
        JsonNode submitted = body(submit(ownerA, submitKey, etag(policyVersion), submitJson(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.body").value(BODY))
                .andExpect(jsonPath("$.contactRemoved").value(false))
                .andExpect(jsonPath("$.sourcePolicyVersion").value(policyVersion))
                .andExpect(jsonPath("$.createdByMembershipId").value(ownerA.context().membershipId().value().toString()))
                .andExpect(jsonPath("$.approval").isEmpty())
                .andExpect(jsonPath("$.attempts").isEmpty()));
        String messageId = submitted.get("messageId").asString();
        assertThat(lastLocation).isEqualTo("/api/messages/" + messageId);
        // A replay of the same request returns the same message, 200 and no Location.
        submit(ownerA, submitKey, etag(policyVersion), submitJson(BODY)).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.messageId").value(messageId));

        mvc.perform(get("/api/messages/" + messageId).with(user(viewerA)).header("X-Tenant-Id", tenant(tenantA)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.messageId").value(messageId));

        String approveKey = "approve-" + UUID.randomUUID();
        approve(operatorA, messageId, approveKey, "\"1\"", "{\"decision\":\"APPROVE\",\"note\":\"nota interna\"}")
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approval.decision").value("APPROVE"))
                .andExpect(jsonPath("$.approval.decidedByMembershipId")
                        .value(operatorA.context().membershipId().value().toString()))
                .andExpect(jsonPath("$.approval.note").doesNotExist())
                .andExpect(jsonPath("$.approval.fingerprint").doesNotExist())
                .andExpect(jsonPath("$.attempts").isEmpty())
                .andExpect(jsonPath("$.providerMessageId").isEmpty());
        // A replay is key-dominant: no re-evaluation, the current representation, 200.
        approve(operatorA, messageId, approveKey, "\"1\"", "{\"decision\":\"APPROVE\",\"note\":\"nota interna\"}")
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
        // The original submit replay now answers with the later state too (ADR 0023 §4.2).
        submit(ownerA, submitKey, etag(policyVersion), submitJson(BODY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        MessageApproval stored = inContext(ownerA, () -> messages.findApprovalByMessage(tenantA.id().value(),
                UUID.fromString(messageId))).orElseThrow();
        assertThat(stored.decision()).isEqualTo(ApprovalDecision.APPROVE);
        assertThat(stored.fingerprint()).isPresent();
        assertThat(sendAttempts()).isZero();

        mvc.perform(get("/api/messages/" + messageId + "/events").with(user(viewerA))
                        .header("X-Tenant-Id", tenant(tenantA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[1].type").value("APPROVED"))
                .andExpect(jsonPath("$.events[1].membershipId").doesNotExist());
        mvc.perform(get("/api/messages").param("status", "APPROVED").param("customerId", customer.id().value().toString())
                        .with(user(viewerA)).header("X-Tenant-Id", tenant(tenantA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].messageId").value(messageId))
                .andExpect(jsonPath("$.items[0].body").doesNotExist())
                .andExpect(jsonPath("$.items[0].recipientPhone").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void viewersAreDeniedEveryCommandBeforeTheirInputIsValidated() throws Exception {
        String messageId = submittedMessageId();
        submit(viewerA, "v-" + UUID.randomUUID(), etag(policyVersion), submitJson(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        submit(viewerA, null, "garbage", "{not json").andExpect(status().isForbidden());
        approve(viewerA, messageId, "v-" + UUID.randomUUID(), "\"1\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isForbidden()).andExpect(content().contentType(PROBLEM));
        approve(viewerA, messageId, null, null, "{\"decision\":\"SHIP_IT\"}").andExpect(status().isForbidden());
        cancel(viewerA, messageId, "v-" + UUID.randomUUID(), "\"1\"", null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        cancel(viewerA, messageId, null, "W/\"1\"", "{\"x\":1}").andExpect(status().isForbidden());

        // An operator holds MESSAGE_DRAFT, which is what cancelling a pending message needs (T4).
        cancel(operatorA, messageId, "c-" + UUID.randomUUID(), "\"1\"", null)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(messageStatus(messageId)).isEqualTo(MessageStatus.CANCELLED);
    }

    @Test
    void anotherTenantsIdsAreNotFoundAndItsListIsEmpty() throws Exception {
        String messageId = submittedMessageId();
        mvc.perform(get("/api/messages/" + messageId).with(user(ownerB)).header("X-Tenant-Id", tenant(tenantB)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/messages/" + messageId + "/events").with(user(ownerB))
                        .header("X-Tenant-Id", tenant(tenantB)))
                .andExpect(status().isNotFound());
        approve(ownerB, messageId, "b-" + UUID.randomUUID(), "\"1\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        cancel(ownerB, messageId, "b-" + UUID.randomUUID(), "\"1\"", null).andExpect(status().isNotFound());
        mvc.perform(post("/api/customers/" + customer.id().value() + "/messages").with(user(ownerB)).with(csrf())
                        .header("X-Tenant-Id", tenant(tenantB)).header("Idempotency-Key", "b-" + UUID.randomUUID())
                        .header("If-Match", etag(policyVersion)).contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson(BODY)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/messages").with(user(ownerB)).header("X-Tenant-Id", tenant(tenantB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        // A member of A presenting B's tenant header has no membership there.
        mvc.perform(get("/api/messages/" + messageId).with(user(ownerA)).header("X-Tenant-Id", tenant(tenantB)))
                .andExpect(status().isForbidden());
        assertThat(messageStatus(messageId)).isEqualTo(MessageStatus.PENDING_APPROVAL);
    }

    @Test
    void bodiesAreReadStrictlySoNoStateOrBindingCanBeMassAssigned() throws Exception {
        for (String invalid : List.of(
                submitJson(BODY).replace("}", ",\"status\":\"APPROVED\"}"),
                submitJson(BODY).replace("}", ",\"recipientPhone\":\"+50600000099\"}"),
                submitJson(BODY).replace("\"GENERAL_CHECK_IN\"", "\"SELL_HARD\""),
                submitJson(BODY).replace("\"GENERAL_CHECK_IN\"", "0"),
                submitJson(BODY).replace("\"MANUAL\"", "\"AI_APPROVED\""),
                submitJson(BODY).replace(",\"locale\":\"es-419\"", ""),
                "[]", "null", "{not json")) {
            submit(ownerA, "m-" + UUID.randomUUID(), etag(policyVersion), invalid)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.detail").doesNotExist());
        }
        assertThat(messageCount()).isZero();

        String messageId = submittedMessageId();
        for (String invalid : List.of("{\"decision\":\"APPROVE\",\"fingerprint\":\"" + "a".repeat(64) + "\"}",
                "{\"decision\":\"APPROVE\",\"status\":\"SENT\"}", "{\"decision\":\"SEND\"}", "{}",
                "{\"decision\":\"APPROVE\",\"note\":\"" + "n".repeat(501) + "\"}")) {
            approve(ownerA, messageId, "m-" + UUID.randomUUID(), "\"1\"", invalid)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        cancel(ownerA, messageId, "m-" + UUID.randomUUID(), "\"1\"", "{\"status\":\"CANCELLED\"}")
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages").param("status", "DRAFT").with(user(ownerA))
                .header("X-Tenant-Id", tenant(tenantA))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages").param("limit", "101").with(user(ownerA))
                .header("X-Tenant-Id", tenant(tenantA))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages").param("cursor", "not-a-cursor").with(user(ownerA))
                .header("X-Tenant-Id", tenant(tenantA))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages/not-a-uuid").with(user(ownerA)).header("X-Tenant-Id", tenant(tenantA)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(messageStatus(messageId)).isEqualTo(MessageStatus.PENDING_APPROVAL);
    }

    @Test
    void unsafeMethodsWithoutCsrfAreRefusedAndWriteNothing() throws Exception {
        mvc.perform(post("/api/customers/" + customer.id().value() + "/messages").with(user(ownerA))
                        .header("X-Tenant-Id", tenant(tenantA)).header("Idempotency-Key", "x-" + UUID.randomUUID())
                        .header("If-Match", etag(policyVersion)).contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson(BODY)))
                .andExpect(status().isForbidden());
        assertThat(messageCount()).isZero();
        String messageId = submittedMessageId();
        mvc.perform(post("/api/messages/" + messageId + "/approvals").with(user(ownerA))
                        .header("X-Tenant-Id", tenant(tenantA)).header("Idempotency-Key", "x-" + UUID.randomUUID())
                        .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
        assertThat(messageStatus(messageId)).isEqualTo(MessageStatus.PENDING_APPROVAL);
    }

    @Test
    void preconditionsAndIdempotencyKeysAreEnforced() throws Exception {
        submit(ownerA, "p-" + UUID.randomUUID(), etag(policyVersion + 1), submitJson(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        submit(ownerA, null, etag(policyVersion), submitJson(BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        submit(ownerA, "bad key with spaces", etag(policyVersion), submitJson(BODY))
                .andExpect(status().isBadRequest());
        String messageId = submittedMessageId();

        for (String malformed : List.of("1", "W/\"1\"", "\"01\"", "\"1\", \"2\"", "*")) {
            approve(ownerA, messageId, "p-" + UUID.randomUUID(), malformed, "{\"decision\":\"APPROVE\"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        approve(ownerA, messageId, "p-" + UUID.randomUUID(), null, "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isBadRequest());
        approve(ownerA, messageId, "p-" + UUID.randomUUID(), "\"7\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));

        String key = "same-" + UUID.randomUUID();
        approve(ownerA, messageId, key, "\"1\"", "{\"decision\":\"REJECT\",\"note\":\"no\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REJECTED"));
        approve(ownerA, messageId, key, "\"1\"", "{\"decision\":\"REJECT\",\"note\":\"otra nota\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        approve(ownerA, messageId, key, "\"1\"", "{\"decision\":\"APPROVE\",\"note\":\"no\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        // A terminal message accepts no further decision.
        approve(ownerA, messageId, "p-" + UUID.randomUUID(), "\"2\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    }

    @Test
    void approvalRevalidatesConsentTemplateAndSenderFromAuthoritativeState() throws Exception {
        String messageId = submittedMessageId();
        setConsent(ConsentStatus.REVOKED);
        approve(ownerA, messageId, "r-" + UUID.randomUUID(), "\"1\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_CONTACT_CONSENT"));
        setConsent(ConsentStatus.GRANTED);

        templates.parameterNames = List.of("customer_name", "coupon_code");
        approve(ownerA, messageId, "r-" + UUID.randomUUID(), "\"1\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEMPLATE_NOT_MAPPED"));
        templates.parameterNames = List.of("customer_name", "message_body");

        safety.configured = false;
        approve(ownerA, messageId, "r-" + UUID.randomUUID(), "\"1\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INTEGRATION_DISABLED"));
        safety.configured = true;

        assertThat(messageStatus(messageId)).isEqualTo(MessageStatus.PENDING_APPROVAL);
        approve(ownerA, messageId, "r-" + UUID.randomUUID(), "\"1\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void anApprovalCoversOnlyWhatWasApprovedAndChangingTheTextNeedsANewReview() throws Exception {
        String first = submittedMessageId();
        approve(ownerA, first, "e-" + UUID.randomUUID(), "\"1\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isCreated());
        assertThat(covered(first)).isTrue();

        templates.version = 2;
        assertThat(covered(first)).as("edited template mapping").isFalse();
        templates.version = 1;
        safety.credentialRevision = 2;
        assertThat(covered(first)).as("rotated credential").isFalse();
        safety.credentialRevision = 1;
        setConsent(ConsentStatus.REVOKED);
        setConsent(ConsentStatus.GRANTED);
        assertThat(covered(first)).as("consent revoked and granted again").isFalse();

        // There is no edit route: new text is a cancellation (T5) plus a new message that needs its own approval.
        mvc.perform(post("/api/messages/" + first + "/edits").with(user(ownerA)).with(csrf())
                        .header("X-Tenant-Id", tenant(tenantA)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        cancel(operatorA, first, "e-" + UUID.randomUUID(), "\"2\"", "{\"note\":\"cambio de texto\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CANCELLED"));
        long currentPolicy = inContext(ownerA, () -> followUps.evaluateSnapshot(customer.id())).policyVersion();
        String second = body(submit(ownerA, "e-" + UUID.randomUUID(), etag(currentPolicy),
                submitJson(BODY + " Saludos.")).andExpect(status().isCreated())).get("messageId").asString();
        assertThat(messageStatus(second)).isEqualTo(MessageStatus.PENDING_APPROVAL);

        mvc.perform(get("/api/messages").param("limit", "1").with(user(ownerA)).header("X-Tenant-Id", tenant(tenantA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].messageId").value(second))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }

    @Test
    void anAuditFailureRollsTheApprovalBack() throws Exception {
        String messageId = submittedMessageId();
        doThrow(new AuditPersistenceException()).when(audit).approved(any());
        approve(ownerA, messageId, "a-" + UUID.randomUUID(), "\"1\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
        assertThat(messageStatus(messageId)).isEqualTo(MessageStatus.PENDING_APPROVAL);
        assertThat(inContext(ownerA, () -> messages.findApprovalByMessage(tenantA.id().value(),
                UUID.fromString(messageId)))).isEmpty();
    }

    @Test
    void consentRevisionStartsANewRunOnlyAfterARevocation() throws Exception {
        long granted = consentRevision();
        setConsent(ConsentStatus.GRANTED);
        assertThat(consentRevision()).as("GRANTED over GRANTED keeps the run").isEqualTo(granted);
        setConsent(ConsentStatus.REVOKED);
        assertThat(inContext(ownerA, () -> contactPolicies.whatsAppConsentRevision(customers.get(customer.id()),
                contactId))).isEmpty();
        setConsent(ConsentStatus.GRANTED);
        assertThat(consentRevision()).isGreaterThan(granted);
    }

    private boolean covered(String messageId) throws Exception {
        UUID id = UUID.fromString(messageId);
        return inContext(ownerA, () -> {
            OutboundMessage message = messages.findById(tenantA.id().value(), id).orElseThrow();
            var approval = messages.findApprovalByMessage(tenantA.id().value(), id).orElseThrow();
            var sender = safety.configuredSender(tenantA.id()).orElseThrow();
            return approval.fingerprint().orElseThrow()
                    .covers(bindings.bind(message, customers.get(customer.id()), sender));
        });
    }

    private long consentRevision() throws Exception {
        return inContext(ownerA, () -> contactPolicies.whatsAppConsentRevision(customers.get(customer.id()),
                contactId)).orElseThrow();
    }

    private void setConsent(ConsentStatus status) throws Exception {
        var policy = inContext(ownerA, () -> contacts.get(customer.id()));
        inContext(ownerA, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP, status,
                ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
    }

    private String submittedMessageId() throws Exception {
        return body(submit(ownerA, "s-" + UUID.randomUUID(), etag(policyVersion), submitJson(BODY))
                .andExpect(status().isCreated())).get("messageId").asString();
    }

    private String submitJson(String text) {
        return "{\"contactId\":\"" + contactId + "\",\"action\":\"GENERAL_CHECK_IN\","
                + "\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"" + text + "\",\"locale\":\"es-419\","
                + "\"origin\":\"MANUAL\"}";
    }

    private ResultActions submit(Member who, String key, String ifMatch, String content) throws Exception {
        return mvc.perform(command(post("/api/customers/" + customer.id().value() + "/messages"), who, key, ifMatch,
                content));
    }

    private ResultActions approve(Member who, String messageId, String key, String ifMatch, String content)
            throws Exception {
        return mvc.perform(command(post("/api/messages/" + messageId + "/approvals"), who, key, ifMatch, content));
    }

    private ResultActions cancel(Member who, String messageId, String key, String ifMatch, String content)
            throws Exception {
        return mvc.perform(command(post("/api/messages/" + messageId + "/cancellations"), who, key, ifMatch,
                content));
    }

    private MockHttpServletRequestBuilder command(MockHttpServletRequestBuilder request, Member who, String key,
            String ifMatch, String content) {
        Tenant tenant = who == ownerB ? tenantB : tenantA;
        request.with(user(who)).with(csrf()).header("X-Tenant-Id", tenant(tenant));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        if (content != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return request;
    }

    private JsonNode body(ResultActions result) throws Exception {
        var response = result.andReturn().getResponse();
        lastLocation = response.getHeader("Location");
        return json.readTree(response.getContentAsString());
    }

    private MessageStatus messageStatus(String messageId) throws Exception {
        return inContext(ownerA, () -> messages.findById(tenantA.id().value(), UUID.fromString(messageId)))
                .orElseThrow().status();
    }

    private int messageCount() throws Exception {
        return inContext(ownerA, () -> jdbc.queryForObject(
                "SELECT count(*) FROM dokene.outbound_messages WHERE tenant_id = ?", Integer.class,
                tenantA.id().value()));
    }

    private int sendAttempts() throws Exception {
        return inContext(ownerA, () -> jdbc.queryForObject(
                "SELECT count(*) FROM dokene.outbound_send_attempts WHERE tenant_id = ?", Integer.class,
                tenantA.id().value()));
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }

    private static String tenant(Tenant tenant) {
        return tenant.id().value().toString();
    }

    private Member member(Tenant tenant, TenantRole role) {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        TenantMembership membership = seedMembership(memberships, contexts, tenant.id(), identity, role, Instant.now());
        return new Member(identity, context(membership));
    }

    private <T> T inContext(Member member, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(member.context(), operation::call));
    }

    private static RequestPostProcessor user(Member member) {
        var principal = new TestPrincipal(member.identity());
        return authentication(new UsernamePasswordAuthenticationToken(principal, "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
}
