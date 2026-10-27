// ABOUTME: Proves POST /api/messages/{id}/resolutions (T12, issue 140) over HTTP against Postgres: role matrix, If-Match,
// ABOUTME: state guard, replay, cross-tenant ids, the stored note, its own audit type, and no provider call ever.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusResult;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusSink;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.ProviderContext;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubSafetyControl;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubTemplateResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "dokene.ai.provider=fake")
@Import({MappedGeneralFollowUpGateConfiguration.class, ScriptedMessagingProvider.Configuration.class})
class MessageResolutionIntegrationTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";
    private static final String PROBLEM = "application/problem+json";
    private static final String FAILED = "{\"outcome\":\"FAILED\",\"note\":\"Not in the Meta delivery log\"}";

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
    @Autowired ProviderContextProvider providerContexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired CustomerService customers;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired FollowUpService followUps;
    @Autowired OutboundMessageRepository messages;
    @Autowired DeliveryStatusSink sink;
    @Autowired StubTemplateResolver templates;
    @Autowired StubSafetyControl safety;
    @Autowired ScriptedMessagingProvider provider;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean TenantContextResolver resolver;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;
    private Tenant tenantA;
    private Tenant tenantB;
    private Member ownerA;
    private Member adminA;
    private Member operatorA;
    private Member viewerA;
    private Member adminB;
    private Customer customer;
    private UUID contactId;
    private long policyVersion;

    private record Member(IdentityId identity, TenantContext context, Tenant tenant, TenantMembership membership) {
    }

    @BeforeEach
    void setUp() throws Exception {
        MappedGeneralFollowUpGateConfiguration.reset(templates, safety);
        safety.blockedWith = null;
        provider.reset();
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, contexts, "Resolution A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, contexts, "Resolution B " + UUID.randomUUID(), now);
        ownerA = member(tenantA, TenantRole.OWNER);
        adminA = member(tenantA, TenantRole.ADMIN);
        operatorA = member(tenantA, TenantRole.OPERATOR);
        viewerA = member(tenantA, TenantRole.VIEWER);
        adminB = member(tenantB, TenantRole.ADMIN);
        customer = inContext(ownerA, () -> customers.create("Ana Pérez", null,
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
        policyVersion = inContext(ownerA, () -> followUps.evaluateSnapshot(customer.id())).policyVersion();
    }

    @AfterEach
    void resetStubs() {
        MappedGeneralFollowUpGateConfiguration.reset(templates, safety);
    }

    /** P3-PERM-07 allow half, P3-DUP-08: an owner resolves as FAILED, a replay returns the same result with 200. */
    @Test
    void anOwnerResolvesAnUnknownOutcomeAsFailedAndAReplayReturnsTheSameResult() throws Exception {
        Unknown unknown = unknownOutcome(Optional.empty());
        String key = "r-" + UUID.randomUUID();
        // A human may only declare FAILED: SENT is not a contract value, so the body is invalid input.
        resolve(ownerA, unknown.id(), "r-" + UUID.randomUUID(), unknown.etag(), "{\"outcome\":\"SENT\"}")
                .andExpect(status().isBadRequest()).andExpect(content().contentType(PROBLEM))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        long resolvedVersion = unknown.version() + 1;
        resolve(ownerA, unknown.id(), key, unknown.etag(), FAILED)
                .andExpect(status().isCreated()).andExpect(header().string("ETag", etag(resolvedVersion)))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.outcomeUnknown").value(false))
                .andExpect(jsonPath("$.failureCategory").value("UNKNOWN"))
                .andExpect(jsonPath("$.providerMessageId").isEmpty())
                .andExpect(jsonPath("$.sentAt").isEmpty())
                .andExpect(jsonPath("$.note").doesNotExist());
        resolve(ownerA, unknown.id(), key, unknown.etag(), FAILED)
                .andExpect(status().isOk()).andExpect(header().string("ETag", etag(resolvedVersion)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCategory").value("UNKNOWN"));
        // The same key with a different request is a reuse, not a replay.
        resolve(ownerA, unknown.id(), key, unknown.etag(), "{\"outcome\":\"FAILED\",\"note\":\"other\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        OutboundMessage resolved = message(unknown.id());
        assertThat(resolved.status()).isEqualTo(MessageStatus.FAILED);
        assertThat(resolved.outcomeUnknown()).isFalse();
        assertThat(resolved.failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(resolved.version()).isEqualTo(resolvedVersion);
        assertThat(lastEventType(unknown.id())).isEqualTo(MessageEventType.SEND_FAILED.name());
        // A human declaration has its own audit type, never the provider failure's (ADR 0023 §2, amended).
        assertThat(audits(unknown.id(), "MESSAGE_SEND_RESOLVED", ownerA)).isEqualTo(1);
        assertThat(audits(unknown.id(), "MESSAGE_SEND_FAILED", null)).isZero();
        // The note is kept on the resolution row only, once, with the resolving member; the replay wrote no second.
        assertThat(resolutions(unknown.id())).singleElement().satisfies(row -> {
            assertThat(row.get("note")).isEqualTo("Not in the Meta delivery log");
            assertThat(row.get("resolved_by_membership_id")).isEqualTo(ownerA.context().membershipId().value());
            assertThat(row.get("resolved_by_actor_id")).isEqualTo(ownerA.identity().value());
        });
        assertThat(provider.calls()).hasSize(1);

        mvc.perform(get("/api/messages/" + unknown.id() + "/events").with(user(viewerA))
                        .header("X-Tenant-Id", tenant(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[-1:].type").value("SEND_FAILED"))
                .andExpect(jsonPath("$.events[-1:].actorKind").value("MEMBER"));
    }

    /** P3-PERM-07: ADMIN holds INTEGRATION_MANAGE too. */
    @Test
    void anAdminMayResolve() throws Exception {
        Unknown unknown = unknownOutcome(Optional.empty());
        resolve(adminA, unknown.id(), "r-" + UUID.randomUUID(), unknown.etag(), FAILED)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(audits(unknown.id(), "MESSAGE_SEND_RESOLVED", adminA)).isEqualTo(1);
        assertThat(provider.calls()).hasSize(1);
    }

    /** P3-PERM-07 deny half: operator and viewer are refused before their input is read, each leaving one denial. */
    @Test
    void operatorsAndViewersAreDeniedAndTheDenialIsAudited() throws Exception {
        Unknown unknown = unknownOutcome(Optional.empty());
        for (Member denied : List.of(operatorA, viewerA)) {
            resolve(denied, unknown.id(), "d-" + UUID.randomUUID(), unknown.etag(), FAILED)
                    .andExpect(status().isForbidden()).andExpect(content().contentType(PROBLEM))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            // Authorization runs before binding (issue 162): garbage input is still a 403, not a 400.
            resolve(denied, unknown.id(), null, "W/\"x\"", "{not json").andExpect(status().isForbidden());
            assertThat(denials(denied)).containsExactly(TenantPermission.INTEGRATION_MANAGE.name(),
                    TenantPermission.INTEGRATION_MANAGE.name());
        }
        OutboundMessage after = message(unknown.id());
        assertThat(after.status()).isEqualTo(MessageStatus.SENDING);
        assertThat(after.outcomeUnknown()).isTrue();
        assertThat(after.version()).isEqualTo(unknown.version());
        assertThat(audits(unknown.id(), "MESSAGE_SEND_RESOLVED", null)).isZero();
        assertThat(resolutions(unknown.id())).isEmpty();
        assertThat(provider.calls()).hasSize(1);
    }

    /**
     * The route admits the admin on its cached role; a revocation committed before the customer lock is caught by the
     * re-read under it (ADR 0028 §2 step 1), denied with an audit row, and leaves nothing behind.
     */
    @Test
    void aMembershipRevokedAfterTheRouteCheckIsRefusedUnderTheLock() throws Exception {
        Unknown unknown = unknownOutcome(Optional.empty());
        TenantMembership membership = adminA.membership();
        // The request's context is built from a membership that is still active; the revocation then commits.
        doAnswer(invocation -> {
            Object resolved = invocation.callRealMethod();
            membership.revoke(Instant.now());
            contexts.runWithTenantId(tenantA.id(), () -> memberships.save(membership));
            return resolved;
        }).doCallRealMethod().when(resolver).resolve(eq(adminA.identity()), eq(tenantA.id()));

        resolve(adminA, unknown.id(), "r-" + UUID.randomUUID(), unknown.etag(), FAILED)
                .andExpect(status().isForbidden()).andExpect(content().contentType(PROBLEM))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // Once by the request filter (before the revocation), once by the re-read under the customer lock.
        verify(resolver, times(2)).resolve(eq(adminA.identity()), eq(tenantA.id()));
        assertThat(denials(adminA)).containsExactly(TenantPermission.INTEGRATION_MANAGE.name());
        OutboundMessage after = message(unknown.id());
        assertThat(after.status()).isEqualTo(MessageStatus.SENDING);
        assertThat(after.outcomeUnknown()).isTrue();
        assertThat(after.version()).isEqualTo(unknown.version());
        assertThat(resolutions(unknown.id())).isEmpty();
        assertThat(audits(unknown.id(), "MESSAGE_SEND_RESOLVED", null)).isZero();
        assertThat(provider.calls()).hasSize(1);
    }

    /** P3-APPR-12: a delivery report advances the version, so the ETag the admin held is stale. */
    @Test
    void anETagMadeStaleByADeliveryReportIsRefused() throws Exception {
        String providerId = "wamid.RESOLVE-" + Long.toHexString(System.nanoTime());
        Unknown unknown = unknownOutcome(Optional.of(providerId));
        DeliveryStatusResult applied = asProvider(() -> sink.apply(new DeliveryStatusReport(providerId,
                DeliveryStatus.SENT, Instant.now(), Optional.empty())));
        assertThat(applied).isEqualTo(new DeliveryStatusResult.Applied(MessageStatus.SENDING, MessageStatus.SENT));

        resolve(adminA, unknown.id(), "r-" + UUID.randomUUID(), unknown.etag(), FAILED)
                .andExpect(status().isConflict()).andExpect(content().contentType(PROBLEM))
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        // With the fresh ETag the message is no longer unknown, so T12 does not apply.
        resolve(adminA, unknown.id(), "r-" + UUID.randomUUID(), etag(message(unknown.id()).version()), FAILED)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        assertThat(message(unknown.id()).status()).isEqualTo(MessageStatus.SENT);
        assertThat(provider.calls()).hasSize(1);
    }

    /** T12 is accepted only while the outcome is unknown; a strict If-Match is required. */
    @Test
    void aMessageWhoseOutcomeIsKnownCannotBeResolvedAndIfMatchIsStrict() throws Exception {
        String messageId = submit();
        approve(messageId);
        resolve(ownerA, messageId, "r-" + UUID.randomUUID(), "\"2\"", FAILED)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        resolve(ownerA, messageId, "r-" + UUID.randomUUID(), "\"1\"", FAILED)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        resolve(ownerA, messageId, "r-" + UUID.randomUUID(), null, FAILED)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        resolve(ownerA, messageId, "r-" + UUID.randomUUID(), "W/\"2\"", FAILED)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        resolve(ownerA, messageId, null, "\"2\"", FAILED)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        for (String invalid : List.of("{}", "{\"outcome\":\"FAILED\",\"status\":\"SENT\"}",
                "{\"outcome\":\"FAILED\",\"note\":\"" + "n".repeat(501) + "\"}")) {
            resolve(ownerA, messageId, "r-" + UUID.randomUUID(), "\"2\"", invalid)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        resolve(ownerA, messageId, "r-" + UUID.randomUUID(), "\"2\"", null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(message(messageId).status()).isEqualTo(MessageStatus.APPROVED);
        assertThat(message(messageId).version()).isEqualTo(2);
        assertThat(provider.calls()).isEmpty();
    }

    /** P3-IDOR-05, resolution half: another tenant's unknown outcome is not found and does not change. */
    @Test
    void anotherTenantsMessageIsNotFound() throws Exception {
        Unknown unknown = unknownOutcome(Optional.empty());
        resolve(adminB, unknown.id(), "b-" + UUID.randomUUID(), unknown.etag(), FAILED)
                .andExpect(status().isNotFound()).andExpect(content().contentType(PROBLEM))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        resolve(adminB, UUID.randomUUID().toString(), "b-" + UUID.randomUUID(), "\"1\"", FAILED)
                .andExpect(status().isNotFound());
        OutboundMessage after = message(unknown.id());
        assertThat(after.status()).isEqualTo(MessageStatus.SENDING);
        assertThat(after.version()).isEqualTo(unknown.version());
        assertThat(resolutions(unknown.id())).isEmpty();
        assertThat(provider.calls()).hasSize(1);
    }

    private record Unknown(String id, long version) {
        String etag() {
            return MessageResolutionIntegrationTest.etag(version);
        }
    }

    /** Submit, approve and send with the provider answering an unknown outcome: SENDING with outcomeUnknown set. */
    private Unknown unknownOutcome(Optional<String> providerMessageId) throws Exception {
        String messageId = submit();
        approve(messageId);
        provider.next = () -> new ProviderSendResult.OutcomeUnknown(providerMessageId);
        String etag = mvc.perform(command(post("/api/messages/" + messageId + "/sends"), ownerA,
                        "s-" + UUID.randomUUID(), "\"2\"", null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SENDING"))
                .andExpect(jsonPath("$.outcomeUnknown").value(true))
                .andReturn().getResponse().getHeader("ETag");
        assertThat(provider.calls()).hasSize(1);
        return new Unknown(messageId, Long.parseLong(etag.replace("\"", "")));
    }

    private String submit() throws Exception {
        String content = "{\"contactId\":\"" + contactId + "\",\"action\":\"GENERAL_CHECK_IN\","
                + "\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"" + BODY + "\",\"locale\":\"es-419\","
                + "\"origin\":\"MANUAL\"}";
        var response = mvc.perform(command(post("/api/customers/" + customer.id().value() + "/messages"), ownerA,
                        "s-" + UUID.randomUUID(), etag(policyVersion), content))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("messageId").asString();
    }

    private void approve(String messageId) throws Exception {
        var detail = mvc.perform(get("/api/messages/" + messageId).with(user(ownerA))
                .header("X-Tenant-Id", tenant(tenantA))).andReturn().getResponse().getContentAsString();
        String revision = json.readTree(detail).path("template").path("revision").asString();
        mvc.perform(command(post("/api/messages/" + messageId + "/approvals"), ownerA, "a-" + UUID.randomUUID(),
                        "\"1\"", "{\"decision\":\"APPROVE\",\"templateRevision\":\"" + revision + "\"}"))
                .andExpect(status().isCreated());
    }

    private ResultActions resolve(Member who, String messageId, String key, String ifMatch, String content)
            throws Exception {
        return mvc.perform(command(post("/api/messages/" + messageId + "/resolutions"), who, key, ifMatch, content));
    }

    private MockHttpServletRequestBuilder command(MockHttpServletRequestBuilder request, Member who, String key,
            String ifMatch, String content) {
        request.with(user(who)).with(csrf()).header("X-Tenant-Id", tenant(who.tenant()));
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

    private OutboundMessage message(String messageId) throws Exception {
        return inContext(ownerA, () -> messages.findById(tenantA.id().value(), UUID.fromString(messageId)))
                .orElseThrow();
    }

    private String lastEventType(String messageId) throws Exception {
        return inContext(ownerA, () -> jdbc.queryForObject("""
                SELECT event_type FROM dokene.outbound_message_events
                WHERE tenant_id = ? AND message_id = ? ORDER BY sequence_number DESC LIMIT 1
                """, String.class, tenantA.id().value(), UUID.fromString(messageId)));
    }

    /** Audit rows of {@code type} targeting the message, attributed to {@code by} when given. */
    private int audits(String messageId, String type, Member by) throws Exception {
        return inContext(ownerA, () -> jdbc.queryForObject("""
                SELECT count(*) FROM dokene.audit_events
                WHERE tenant_id = ? AND event_type = ? AND target_id = ?
                  AND (?::uuid IS NULL OR membership_id = ?::uuid)
                """, Integer.class, tenantA.id().value(), type, UUID.fromString(messageId),
                by == null ? null : by.context().membershipId().value(),
                by == null ? null : by.context().membershipId().value()));
    }

    private List<Map<String, Object>> resolutions(String messageId) throws Exception {
        return inContext(ownerA, () -> jdbc.queryForList("""
                SELECT note, resolved_by_membership_id, resolved_by_actor_id FROM dokene.outbound_message_resolutions
                WHERE tenant_id = ? AND message_id = ?
                """, tenantA.id().value(), UUID.fromString(messageId)));
    }

    /** The permission each AUTHORIZATION_DENIED row names for this member, oldest first. */
    private List<String> denials(Member member) throws Exception {
        return inContext(ownerA, () -> jdbc.queryForList("""
                SELECT permission FROM dokene.audit_events
                WHERE tenant_id = ? AND membership_id = ? AND event_type = 'AUTHORIZATION_DENIED'
                ORDER BY occurred_at, id
                """, String.class, tenantA.id().value(), member.context().membershipId().value()));
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
        return new Member(identity, context(membership), tenant, membership);
    }

    private <T> T asProvider(Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> providerContexts.callWithContext(new ProviderContext(tenantA.id()), operation::call));
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
