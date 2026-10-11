// ABOUTME: Drives the local provider end to end against Postgres: suffix outcomes and after commit delivery reports.
// ABOUTME: Runs with dokene.messaging.provider=local, a 0s delay, a mutable clock and bounded polls on the detail.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.integration.config.IntegrationConfigurationService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"dokene.ai.provider=fake",
        "dokene.messaging.template-gate.stub.enabled-intents=GENERAL_FOLLOW_UP",
        "dokene.messaging.outbound.enabled=true",
        "dokene.messaging.provider=local", "dokene.messaging.local.delivery-delay=0s"})
@Import(LocalProviderDeliveryIntegrationTest.MutableClockConfiguration.class)
class LocalProviderDeliveryIntegrationTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";
    private static final Duration POLL_BUDGET = Duration.ofSeconds(10);

    /** Lets one test cross a full cadence without waiting thirty days. */
    @TestConfiguration
    static class MutableClockConfiguration {
        static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.now());

        @Bean
        @Primary
        Clock mutableClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return NOW.get(); }
            };
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
    @Autowired OutboundMessageRepository messages;
    @Autowired JdbcTemplate jdbc;
    @Autowired IntegrationConfigurationService integrations;
    @Autowired ConfigurableEnvironment environment;

    private Tenant tenant;
    private IdentityId owner;
    private TenantContext ownerContext;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger("io.github.stevdrey.dokene");

    @BeforeEach
    void setUp() throws Exception {
        MutableClockConfiguration.NOW.set(Instant.now());
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        Instant now = Instant.now();
        tenant = seedTenant(tenants, "Local " + UUID.randomUUID(), now);
        owner = new IdentityId(UUID.randomUUID());
        ownerContext = context(seedMembership(memberships, contexts, tenant.id(), owner, TenantRole.OWNER, now));
        inContext(ownerContext, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        new IntegrationTestSupport(integrations, contexts, auditExecution, environment).enableIntegration(ownerContext);
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        rootLogger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void sendsThroughTheLocalProviderAndLandsDeliveredAfterTheCommit() throws Exception {
        Seeded seeded = seedCustomer("Entregado", "55");
        UUID messageId = approved(seeded);

        mvc.perform(send(messageId, "\"2\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.in(List.of("SENT", "DELIVERED"))))
                .andExpect(jsonPath("$.providerMessageId").value(org.hamcrest.Matchers.startsWith("local-")))
                .andExpect(jsonPath("$.attempts[0].outcome").value("ACCEPTED"));

        JsonNode detail = pollUntil(messageId, "DELIVERED");
        assertThat(detail.get("deliveredAt").isNull()).isFalse();
        String providerMessageId = detail.get("providerMessageId").asString();
        mvc.perform(get("/api/messages/{id}/events", messageId).with(user(owner)).header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[-1:].eventType").value("DELIVERY_UPDATED"))
                .andExpect(jsonPath("$.events[-1:].actorKind").value("PROVIDER"))
                .andExpect(jsonPath("$.events[-1:].statusFrom").value("SENT"))
                .andExpect(jsonPath("$.events[-1:].statusTo").value("DELIVERED"));
        assertThat(auditRows(messageId, "MESSAGE_DELIVERY_UPDATED")).isEqualTo(1);
        for (ILoggingEvent entry : logs.list) {
            assertThat(entry.getFormattedMessage()).doesNotContain(BODY).doesNotContain(seeded.phone())
                    .doesNotContain(providerMessageId);
        }
        assertThat(logs.list).anySatisfy(entry -> assertThat(entry.getFormattedMessage())
                .contains("Local provider simulated a send").contains(tenant.id().value().toString())
                .contains("Accepted"));
    }

    @Test
    void suffix03ReadsFreesTheCustomerAndTheNextCadenceSendsAgain() throws Exception {
        Seeded seeded = seedCustomer("Leído", "03");
        UUID messageId = approved(seeded);
        mvc.perform(send(messageId, "\"2\"")).andExpect(status().isCreated());
        pollUntil(messageId, "READ");

        assertThat(inContext(ownerContext, () -> messages.findOpenByCustomer(tenant.id().value(),
                seeded.customer().id().value(), Optional.empty()))).isEmpty();
        var evaluation = inContext(ownerContext, () -> followUps.evaluateSnapshot(seeded.customer().id()));
        assertThat(evaluation.evaluation().status()).isEqualTo(FollowUpStatus.NOT_YET_DUE);
        assertThat(evaluation.evaluation().anchorDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        mvc.perform(submit(seeded.withPolicyVersion(evaluation.policyVersion()), "submit-" + UUID.randomUUID()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FOLLOW_UP_INELIGIBLE"));

        MutableClockConfiguration.NOW.set(Instant.now().plus(Duration.ofDays(31)));
        var later = inContext(ownerContext, () -> followUps.evaluateSnapshot(seeded.customer().id()));
        assertThat(later.evaluation().eligible()).isTrue();
        UUID next = approved(seeded.withPolicyVersion(later.policyVersion()));
        mvc.perform(send(next, "\"2\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.attempts[0].outcome").value("ACCEPTED"));
        pollUntil(next, "READ");
    }

    @Test
    void suffix04FreezesWithAnIdAndThenDeliversThroughT13() throws Exception {
        Seeded seeded = seedCustomer("Tiempo agotado", "04");
        UUID messageId = approved(seeded);

        mvc.perform(send(messageId, "\"2\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.in(List.of("SENDING", "DELIVERED"))))
                .andExpect(jsonPath("$.providerMessageId").value(org.hamcrest.Matchers.startsWith("local-")))
                .andExpect(jsonPath("$.attempts[0].outcome").value("OUTCOME_UNKNOWN"));

        JsonNode detail = pollUntil(messageId, "DELIVERED");
        assertThat(detail.get("outcomeUnknown").asBoolean()).isFalse();
    }

    @Test
    void suffixes00And01And02LandFailedApprovedAndFrozen() throws Exception {
        UUID rejected = approved(seedCustomer("Rechazado", "00"));
        mvc.perform(send(rejected, "\"2\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.lastAttempt.failureCategory").value("INVALID_RECIPIENT"));

        UUID transientId = approved(seedCustomer("Transitorio", "01"));
        mvc.perform(send(transientId, "\"2\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.lastAttempt.failureCategory").value("RATE_LIMITED"));
        mvc.perform(send(transientId, "\"5\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(send(transientId, "\"8\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.attempts.length()").value(3));
        mvc.perform(send(transientId, "\"11\"")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        UUID frozen = approved(seedCustomer("Congelado", "02"));
        mvc.perform(send(frozen, "\"2\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SENDING"))
                .andExpect(jsonPath("$.outcomeUnknown").value(true))
                .andExpect(jsonPath("$.providerMessageId").value((Object) null));
        Thread.sleep(200);
        mvc.perform(get("/api/messages/{id}", frozen).with(user(owner)).header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENDING"));
    }

    private JsonNode pollUntil(UUID messageId, String expected) throws Exception {
        Instant deadline = Instant.now().plus(POLL_BUDGET);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            MvcResult result = mvc.perform(get("/api/messages/{id}", messageId).with(user(owner))
                    .header("X-Tenant-Id", tenant.id().value())).andExpect(status().isOk()).andReturn();
            last = json.readTree(result.getResponse().getContentAsString());
            if (expected.equals(last.get("status").asString())) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Message " + messageId + " never reached " + expected + "; last: " + last);
    }

    private UUID approved(Seeded target) throws Exception {
        MvcResult result = mvc.perform(submit(target, "submit-" + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn();
        UUID messageId = UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .get("messageId").asString());
        mvc.perform(post("/api/messages/{id}/approvals", messageId).with(user(owner)).with(csrf())
                        .header("X-Tenant-Id", tenant.id().value()).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "approve-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"2\""));
        return messageId;
    }

    private MockHttpServletRequestBuilder send(UUID messageId, String ifMatch) {
        return post("/api/messages/{id}/sends", messageId).with(user(owner)).with(csrf())
                .header("X-Tenant-Id", tenant.id().value()).header("If-Match", ifMatch)
                .header("Idempotency-Key", "send-" + UUID.randomUUID());
    }

    private MockHttpServletRequestBuilder submit(Seeded target, String key) {
        return post("/api/customers/{id}/messages", target.customer().id().value()).with(user(owner)).with(csrf())
                .header("X-Tenant-Id", tenant.id().value()).header("If-Match", "\"" + target.policyVersion() + "\"")
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"contactId":"%s","action":"GENERAL_CHECK_IN","templateIntent":"GENERAL_FOLLOW_UP",
                         "body":"%s","locale":"es-419","origin":"MANUAL"}
                        """.formatted(target.contactId(), BODY));
    }

    private Seeded seedCustomer(String displayName, String suffix) throws Exception {
        Instant now = Instant.now();
        String number = "8888" + String.format("%02d", Math.abs(UUID.randomUUID().hashCode()) % 100) + suffix;
        Customer customer = inContext(ownerContext, () -> customers.create(displayName, null,
                List.of(new PhoneInput(number, "CR", true))));
        UUID contactId = customer.phones().getFirst().id();
        var policy = inContext(ownerContext, () -> contacts.get(customer.id()));
        inContext(ownerContext, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
        inContext(ownerContext, () -> purchases.record(customer.id(), now.minusSeconds(60), "Anchor purchase",
                "anchor-" + UUID.randomUUID()));
        inContext(ownerContext, () -> followUps.configureCustomer(customer.id(), null,
                LocalDate.now(ZoneId.of("UTC")), 0));
        var snapshot = inContext(ownerContext, () -> followUps.evaluateSnapshot(customer.id()));
        assertThat(snapshot.evaluation().status()).isEqualTo(FollowUpStatus.DUE);
        return new Seeded(customer, contactId, "+506" + number, snapshot.policyVersion());
    }

    private int auditRows(UUID messageId, String eventType) throws Exception {
        return inContext(ownerContext, () -> jdbc.queryForObject(
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

    private record Seeded(Customer customer, UUID contactId, String phone, long policyVersion) {
        Seeded withPolicyVersion(long version) {
            return new Seeded(customer, contactId, phone, version);
        }
    }
}
