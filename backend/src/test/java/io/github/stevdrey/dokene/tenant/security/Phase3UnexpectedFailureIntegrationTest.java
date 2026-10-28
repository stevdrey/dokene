// ABOUTME: Unexpected failures on Phase 3 routes answer the closed 500 INTERNAL_ERROR problem (issue 293, P3-KILL-07),
// ABOUTME: while database outages, audit failures and Spring MVC errors keep the answers they already had.
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService;
import io.github.stevdrey.dokene.messaging.application.ApprovalCommand;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageCommandService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageQueryService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.messaging.application.SubmitMessageCommand;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.template.application.TemplateRegistryService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubSafetyControl;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubTemplateResolver;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
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
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "dokene.ai.provider=fake")
@Import({MappedGeneralFollowUpGateConfiguration.class, ScriptedMessagingProvider.Configuration.class})
class Phase3UnexpectedFailureIntegrationTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";
    private static final String PROBLEM = "application/problem+json";
    /** Text an unexpected failure might carry; none of it may reach a response body. */
    private static final String CANARY = "CANARY-UNEXPECTED-FAILURE permission denied for table secret_table";

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
    @Autowired PurchaseService purchases;
    @Autowired FollowUpService followUps;
    @Autowired OutboundMessageCommandService commands;
    @Autowired OutboundMessageRepository messages;
    @Autowired StubTemplateResolver templates;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean StubSafetyControl safety;
    @MockitoSpyBean OutboundMessageQueryService queries;
    @MockitoSpyBean TemplateRegistryService registry;
    @MockitoSpyBean WhatsAppIntegrationService integrations;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;
    private Tenant tenant;
    private IdentityId owner;
    private TenantContext ownerContext;

    @BeforeEach
    void setUp() {
        MappedGeneralFollowUpGateConfiguration.reset(templates, safety);
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        Instant now = Instant.now();
        tenant = seedTenant(tenants, contexts, "Unexpected failure " + UUID.randomUUID(), now);
        owner = new IdentityId(UUID.randomUUID());
        ownerContext = context(seedMembership(memberships, contexts, tenant.id(), owner, TenantRole.OWNER, now));
    }

    @AfterEach
    void resetStubs() {
        MappedGeneralFollowUpGateConfiguration.reset(templates, safety);
    }

    @Test
    void anUnreadableOutboundSwitchAtSendIsAClosedInternalErrorWithNoEffect() throws Exception {
        // P3-KILL-07: the tenant switch lookup fails (as when the runtime role loses SELECT on the integration table).
        OutboundMessage approved = approvedMessage();
        safety.blockedWith = null;
        doThrow(new PermissionDeniedDataAccessException(CANARY, new SQLException(CANARY, "42501")))
                .when(safety).evaluate(any());

        MockHttpServletResponse response = perform(post("/api/messages/" + approved.id() + "/sends")
                .header("Idempotency-Key", "send-" + UUID.randomUUID())
                .header("If-Match", "\"" + approved.version() + "\""));

        assertInternalErrorProblem(response, approved.id());
        OutboundMessage after = inContext(() -> messages.findById(tenant.id().value(), approved.id())).orElseThrow();
        assertThat(after.status()).isEqualTo(MessageStatus.APPROVED);
        assertThat(after.version()).isEqualTo(approved.version());
        assertThat(inContext(() -> messages.findAttempts(tenant.id().value(), approved.id()))).isEmpty();
    }

    @Test
    void everyPhase3RouteFamilyAnswersTheSameClosedProblem() throws Exception {
        UUID messageId = UUID.randomUUID();
        doThrow(new IllegalStateException(CANARY)).when(queries).detail(any());
        doThrow(new IllegalStateException(CANARY)).when(registry).list();
        doThrow(new IllegalStateException(CANARY)).when(integrations).get();

        assertInternalErrorProblem(perform(get("/api/messages/" + messageId)), messageId);
        assertInternalErrorProblem(perform(get("/api/templates")), null);
        assertInternalErrorProblem(perform(get("/api/integrations/whatsapp")), null);
    }

    @Test
    void aDatabaseOutageStillAnswersTheEmptyRetryable503() throws Exception {
        doThrow(new CannotGetJdbcConnectionException(CANARY)).when(queries).detail(any());

        MockHttpServletResponse response = perform(get("/api/messages/" + UUID.randomUUID()));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void anAuditFailureStillAnswersTheEmpty503() throws Exception {
        doThrow(new AuditPersistenceException()).when(registry).list();

        MockHttpServletResponse response = perform(get("/api/templates"));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void springMvcErrorsKeepTheirOwnStatus() throws Exception {
        // No DELETE route exists; Spring answers 405 before any Phase 3 advice applies.
        assertThat(perform(delete("/api/messages/" + UUID.randomUUID())).getStatus()).isEqualTo(405);
        // A malformed id is still the mapped INVALID_INPUT problem, not the fallback.
        MockHttpServletResponse invalid = perform(get("/api/messages/not-a-uuid"));
        assertThat(invalid.getStatus()).isEqualTo(400);
        assertThat(body(invalid).path("code").asString()).isEqualTo("INVALID_INPUT");
    }

    private void assertInternalErrorProblem(MockHttpServletResponse response, UUID echoed) throws Exception {
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith(PROBLEM);
        JsonNode problem = body(response);
        assertThat(problem.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "code");
        assertThat(problem.path("type").asString()).isEqualTo("about:blank");
        assertThat(problem.path("title").asString()).isEqualTo("Internal Server Error");
        assertThat(problem.path("status").asInt()).isEqualTo(500);
        assertThat(problem.path("code").asString()).isEqualTo("INTERNAL_ERROR");
        String raw = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("CANARY", "permission denied", "secret_table", "timestamp", "path");
        if (echoed != null) {
            assertThat(raw).doesNotContain(echoed.toString());
        }
    }

    private OutboundMessage approvedMessage() throws Exception {
        Instant now = Instant.now();
        inContext(() -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        Customer customer = inContext(() -> customers.create("Unexpected failure customer", null, List.of(
                new PhoneInput("8888" + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000), "CR",
                        true))));
        UUID contactId = customer.phones().getFirst().id();
        inContext(() -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, contacts.get(customer.id()).version()));
        inContext(() -> purchases.record(customer.id(), now.minusSeconds(60), "Anchor purchase",
                "anchor-" + UUID.randomUUID()));
        inContext(() -> followUps.configureCustomer(customer.id(), null, LocalDate.now(ZoneId.of("UTC")), 0));
        long policyVersion = inContext(() -> followUps.evaluateSnapshot(customer.id())).policyVersion();
        OutboundMessage pending = inContext(() -> commands.submit(new SubmitMessageCommand(customer.id().value(),
                contactId, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", BODY, "es-419", "MANUAL",
                Long.toString(policyVersion), "submit-" + UUID.randomUUID()))).message();
        OutboundMessage approved = inContext(() -> commands.approve(new ApprovalCommand(pending.id(), null,
                Long.toString(pending.version()), "approve-" + UUID.randomUUID()))).message();
        assertThat(approved.status()).isEqualTo(MessageStatus.APPROVED);
        return approved;
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(csrf()).header("X-Tenant-Id", tenant.id().value().toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(new TestPrincipal(owner), "test",
                        List.of())))).andReturn().getResponse();
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private <T> T inContext(Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(ownerContext, operation::call));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
}
