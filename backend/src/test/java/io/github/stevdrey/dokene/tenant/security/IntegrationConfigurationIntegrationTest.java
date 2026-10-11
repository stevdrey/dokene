// ABOUTME: Drives the integration routes against Postgres (spec 0005 AC-11): ETags, refusals, audit, two tenants, gate.
// ABOUTME: Every log line of the run is captured and proven free of the token, the identifiers and the reference.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
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
import io.github.stevdrey.dokene.integration.config.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.config.SecretRef;
import io.github.stevdrey.dokene.integration.config.SecretValue;
import io.github.stevdrey.dokene.integration.config.TenantMessagingIntegrationRepository;
import io.github.stevdrey.dokene.messaging.application.OutboundGate;
import io.github.stevdrey.dokene.messaging.application.OutboundGateStatus;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
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
        "dokene.messaging.outbound.enabled=true", "dokene.messaging.provider=local",
        "dokene.messaging.local.delivery-delay=0s", "DOKENE_WA_TEST=test-token", "DOKENE_WA_TEST_B=test-token-b"})
class IntegrationConfigurationIntegrationTest {
    private static final String ROUTE = "/api/integrations/whatsapp";
    private static final String SWITCH = ROUTE + "/outbound";
    private static final String PHONE_NUMBER_ID_A = "100000000000000";
    private static final String PHONE_NUMBER_ID_B = "100000000000001";
    private static final String BUSINESS_ACCOUNT_ID = "200000000000000";
    private static final String DISPLAY_NUMBER = "+56922220000";
    private static final String REF_A = "env:DOKENE_WA_TEST";
    private static final String REF_B = "env:DOKENE_WA_TEST_B";
    private static final String DEFAULT_REPRESENTATION = """
            {"provider":"WHATSAPP","phoneNumberId":null,"businessAccountId":null,"displayPhoneNumber":null,
             "secretRef":null,"enabled":false,"outboundEnabled":false,"webhookVerified":false,"version":0,
             "updatedAt":null}
            """;
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    MockMvc mvc;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired ApplicationContext applicationContext;
    @Autowired ConfigurableEnvironment environment;
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
    @Autowired IntegrationSecretResolver resolver;
    @Autowired OutboundGate gate;
    @MockitoSpyBean AuditRecorder auditRecorder;
    @MockitoSpyBean TenantMessagingIntegrationRepository repository;

    private Tenant tenantA;
    private Tenant tenantB;
    private IdentityId ownerA;
    private IdentityId viewerA;
    private IdentityId operatorA;
    private IdentityId ownerB;
    private TenantContext ownerAContext;
    private TenantContext ownerBContext;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        // The two fixed references and phone number ids are unique across every tenant, so rows left by an earlier
        // test (or class) of this JVM are cleared with the migration role before each case.
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM dokene.tenant_messaging_integrations");
        }
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, "Integration A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, "Integration B " + UUID.randomUUID(), now);
        ownerA = new IdentityId(UUID.randomUUID());
        viewerA = new IdentityId(UUID.randomUUID());
        operatorA = new IdentityId(UUID.randomUUID());
        ownerB = new IdentityId(UUID.randomUUID());
        ownerAContext = context(seedMembership(memberships, contexts, tenantA.id(), ownerA, TenantRole.OWNER, now));
        seedMembership(memberships, contexts, tenantA.id(), viewerA, TenantRole.VIEWER, now);
        seedMembership(memberships, contexts, tenantA.id(), operatorA, TenantRole.OPERATOR, now);
        ownerBContext = context(seedMembership(memberships, contexts, tenantB.id(), ownerB, TenantRole.OWNER, now));
        inContext(ownerAContext, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        logs.start();
        rootLogger.addAppender(logs);
    }

    /** AC-10 across the suite: no token value, identifier, display number or reference string reaches any log line. */
    @AfterEach
    void detachLogsAndProveTheyAreClean() {
        rootLogger.detachAppender(logs);
        logs.stop();
        for (ILoggingEvent entry : logs.list) {
            StringBuilder text = new StringBuilder(entry.getFormattedMessage());
            for (IThrowableProxy cause = entry.getThrowableProxy(); cause != null; cause = cause.getCause()) {
                text.append('\n').append(cause.getMessage());
            }
            assertThat(text.toString()).doesNotContain("test-token").doesNotContain(PHONE_NUMBER_ID_A)
                    .doesNotContain(PHONE_NUMBER_ID_B).doesNotContain(BUSINESS_ACCOUNT_ID)
                    .doesNotContain(DISPLAY_NUMBER).doesNotContain("DOKENE_WA_TEST");
        }
    }

    @Test
    void readsTheDefaultThenInsertsUpdatesSwitchesAndSendsWithEtags() throws Exception {
        mvc.perform(read(ownerA, tenantA)).andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(content().json(DEFAULT_REPRESENTATION, JsonCompareMode.STRICT));

        MvcResult inserted = mvc.perform(update(ownerA, tenantA, "\"0\"", bodyA(true)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.provider").value("WHATSAPP"))
                .andExpect(jsonPath("$.phoneNumberId").value(PHONE_NUMBER_ID_A))
                .andExpect(jsonPath("$.displayPhoneNumber").value(DISPLAY_NUMBER))
                .andExpect(jsonPath("$.secretRef").value(REF_A)).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.outboundEnabled").value(false))
                .andExpect(jsonPath("$.webhookVerified").value(false))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty()).andReturn();
        assertThat(inserted.getResponse().getContentAsString()).doesNotContain("test-token")
                .doesNotContain("displayPhoneRegion");
        assertThat(auditRows(ownerAContext, "INTEGRATION_UPDATED"))
                .containsExactly(Map.of("enabled", true, "changed_fields",
                        "PHONE_NUMBER_ID,BUSINESS_ACCOUNT_ID,DISPLAY_PHONE_NUMBER,SECRET_REF,ENABLED"));

        mvc.perform(killSwitch(ownerA, tenantA, "\"1\"", "{\"enabled\":true}")).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\"")).andExpect(jsonPath("$.outboundEnabled").value(true))
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.version").value(2));
        assertThat(auditRows(ownerAContext, "OUTBOUND_KILL_SWITCH_CHANGED"))
                .containsExactly(Map.of("enabled", true, "changed_fields", ""));

        Seeded seeded = seedCustomer("Cliente integración");
        UUID messageId = approved(seeded);
        mvc.perform(send(messageId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.in(List.of("SENT", "DELIVERED"))));

        // A save over an identical record still bumps the version and still audits, with no field names.
        mvc.perform(update(ownerA, tenantA, "\"2\"", bodyA(true))).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\"")).andExpect(jsonPath("$.outboundEnabled").value(true));
        mvc.perform(update(ownerA, tenantA, "\"3\"", body(PHONE_NUMBER_ID_A, "200000000000009", "+56 9 2222 0000",
                "CL", REF_A, "true"))).andExpect(status().isOk()).andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.businessAccountId").value("200000000000009"));
        assertThat(auditRows(ownerAContext, "INTEGRATION_UPDATED")).containsExactly(
                Map.of("enabled", true, "changed_fields",
                        "PHONE_NUMBER_ID,BUSINESS_ACCOUNT_ID,DISPLAY_PHONE_NUMBER,SECRET_REF,ENABLED"),
                Map.of("enabled", true, "changed_fields", ""),
                Map.of("enabled", true, "changed_fields", "BUSINESS_ACCOUNT_ID"));

        // Omitted nullable fields become null; a disabled record may be incomplete.
        mvc.perform(update(ownerA, tenantA, "\"4\"", "{\"enabled\":false}")).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"5\"")).andExpect(jsonPath("$.phoneNumberId").value((Object) null))
                .andExpect(jsonPath("$.secretRef").value((Object) null)).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.outboundEnabled").value(true));
        assertThat(resolver.resolve(SecretRef.parse(REF_A))).contains(new SecretValue("test-token"));
    }

    @Test
    void theGateLadderFollowsTheTwoFlagsWithoutARestart() throws Exception {
        Seeded seeded = seedCustomer("Escalera");
        UUID messageId = approved(seeded);

        mvc.perform(send(messageId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "INTEGRATION_DISABLED"));
        mvc.perform(update(ownerA, tenantA, "\"0\"", bodyA(false))).andExpect(status().isOk());
        mvc.perform(send(messageId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "INTEGRATION_DISABLED"));
        mvc.perform(update(ownerA, tenantA, "\"1\"", bodyA(true))).andExpect(status().isOk());
        mvc.perform(send(messageId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "OUTBOUND_DISABLED_FOR_TENANT"));
        mvc.perform(killSwitch(ownerA, tenantA, "\"2\"", "{\"enabled\":true}")).andExpect(status().isOk());
        mvc.perform(send(messageId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.in(List.of("SENT", "DELIVERED"))));
        // The gate runs before the message lock, so the next send is refused by the switch, not by the state.
        mvc.perform(killSwitch(ownerA, tenantA, "\"3\"", "{\"enabled\":false}")).andExpect(status().isOk());
        mvc.perform(send(messageId, "\"2\"", "send-" + UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "OUTBOUND_DISABLED_FOR_TENANT"));
        assertThat(rowCount(ownerAContext, "outbound_send_attempts", messageId)).isEqualTo(1);
    }

    @Test
    void refusesEveryInvalidIncompleteUnresolvableStaleAndForeignSaveWithoutWriting() throws Exception {
        mvc.perform(update(ownerA, tenantA, "\"0\"", body(PHONE_NUMBER_ID_A, BUSINESS_ACCOUNT_ID, "+56 9 2222 0000",
                "CL", null, "true"))).andExpect(status().isUnprocessableContent())
                .andExpect(problem(422, "Unprocessable Content", "INTEGRATION_INCOMPLETE"));
        mvc.perform(update(ownerA, tenantA, "\"0\"", body(PHONE_NUMBER_ID_A, BUSINESS_ACCOUNT_ID, "+56 9 2222 0000",
                "CL", "env:DOKENE_WA_NOT_DEFINED", "true"))).andExpect(status().isUnprocessableContent())
                .andExpect(problem(422, "Unprocessable Content", "SECRET_REF_UNRESOLVABLE"));
        mvc.perform(update(ownerA, tenantA, "\"0\"", body(null, null, null, null, "env:DOKENE_WA_NOT_DEFINED",
                "false"))).andExpect(status().isUnprocessableContent())
                .andExpect(problem(422, "Unprocessable Content", "SECRET_REF_UNRESOLVABLE"));
        for (String malformed : List.of("vault:foo", "env:SPRING_DATASOURCE_PASSWORD", "env:DOKENE_WA_lower")) {
            mvc.perform(update(ownerA, tenantA, "\"7\"", body(null, null, null, null, malformed, "false")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(problem(422, "Unprocessable Content", "SECRET_REF_UNRESOLVABLE"));
        }
        mvc.perform(update(ownerA, tenantA, "\"0\"", body(null, null, "+56912345655", "CL", null, "false")))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(update(ownerA, tenantA, "\"0\"", body(null, null, null, "CL", null, "false")))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(update(ownerA, tenantA, "\"0\"", body(null, null, "+56 9 2222 0000", null, null, "false")))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(update(ownerA, tenantA, "\"0\"", body("12a", null, null, null, null, "false")))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(update(ownerA, tenantA, "\"0\"", "{\"enabled\":false,\"outboundEnabled\":true}"))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(update(ownerA, tenantA, "\"0\"", "{\"enabled\":false,\"version\":3}"))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(update(ownerA, tenantA, "\"0\"", "{\"phoneNumberId\":\"1\"}"))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(update(ownerA, tenantA, "\"0\"", "{\"enabled\":null}"))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(update(ownerA, tenantA, null, bodyA(false)))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(update(ownerA, tenantA, "1", bodyA(false)))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(update(ownerA, tenantA, "W/\"0\"", bodyA(false)))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(update(ownerA, tenantA, "\"7\"", bodyA(false))).andExpect(status().isConflict())
                .andExpect(header().string("ETag", "\"0\"")).andExpect(problem(409, "Conflict", "STALE_VERSION"));
        assertThat(integrationRows(ownerAContext)).isZero();
        assertThat(auditRows(ownerAContext, "INTEGRATION_UPDATED")).isEmpty();

        // Cross tenant uniqueness is the database's: tenant B cannot take A's number or A's reference.
        mvc.perform(update(ownerA, tenantA, "\"0\"", bodyA(true))).andExpect(status().isOk());
        mvc.perform(update(ownerB, tenantB, "\"0\"", body(PHONE_NUMBER_ID_A, BUSINESS_ACCOUNT_ID, null, null, REF_B,
                "false"))).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "PHONE_NUMBER_ID_IN_USE"));
        mvc.perform(update(ownerB, tenantB, "\"0\"", body(PHONE_NUMBER_ID_B, BUSINESS_ACCOUNT_ID, null, null, REF_A,
                "false"))).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "SECRET_REF_IN_USE"));
        assertThat(integrationRows(ownerBContext)).isZero();
        assertThat(auditRows(ownerBContext, "INTEGRATION_UPDATED")).isEmpty();
        mvc.perform(read(ownerA, tenantA)).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.phoneNumberId").value(PHONE_NUMBER_ID_A))
                .andExpect(jsonPath("$.secretRef").value(REF_A));
        // An update is caught the same way as an insert.
        mvc.perform(update(ownerB, tenantB, "\"0\"", body(PHONE_NUMBER_ID_B, null, null, null, REF_B, "false")))
                .andExpect(status().isOk());
        mvc.perform(update(ownerB, tenantB, "\"1\"", body(PHONE_NUMBER_ID_A, null, null, null, REF_B, "false")))
                .andExpect(status().isConflict()).andExpect(problem(409, "Conflict", "PHONE_NUMBER_ID_IN_USE"));
        mvc.perform(read(ownerB, tenantB)).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.phoneNumberId").value(PHONE_NUMBER_ID_B));
    }

    @Test
    void twoFirstWritesRacingEndAsOneSuccessAndOneStaleVersion() throws Exception {
        CountDownLatch firstPaused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                firstPaused.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(repository).insert(any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> mvc.perform(update(ownerA, tenantA, "\"0\"", bodyA(false)))
                    .andReturn().getResponse().getStatus();
            Future<Integer> one = pool.submit(attempt);
            assertThat(firstPaused.await(10, TimeUnit.SECONDS)).isTrue();
            // The second request read "no row" after the first did, and commits first.
            assertThat(pool.submit(attempt).get(10, TimeUnit.SECONDS)).isEqualTo(200);
            release.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(409);
        } finally {
            pool.shutdownNow();
        }
        mvc.perform(read(ownerA, tenantA)).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        assertThat(auditRows(ownerAContext, "INTEGRATION_UPDATED")).hasSize(1);
    }

    @Test
    void theKillSwitchSkipsANoOpAndInsertsARowWhenNoneExists() throws Exception {
        mvc.perform(killSwitch(ownerA, tenantA, "\"0\"", "{\"enabled\":false}")).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(content().json(DEFAULT_REPRESENTATION, JsonCompareMode.STRICT));
        assertThat(integrationRows(ownerAContext)).isZero();
        mvc.perform(killSwitch(ownerA, tenantA, "\"3\"", "{\"enabled\":false}")).andExpect(status().isConflict())
                .andExpect(header().string("ETag", "\"0\"")).andExpect(problem(409, "Conflict", "STALE_VERSION"));
        mvc.perform(killSwitch(ownerA, tenantA, "\"0\"", "{\"enabled\":true,\"secretRef\":\"env:DOKENE_WA_TEST\"}"))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(killSwitch(ownerA, tenantA, "\"0\"", "{}"))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(killSwitch(ownerA, tenantA, null, "{\"enabled\":true}"))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        assertThat(auditRows(ownerAContext, "OUTBOUND_KILL_SWITCH_CHANGED")).isEmpty();

        mvc.perform(killSwitch(ownerA, tenantA, "\"0\"", "{\"enabled\":true}")).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\"")).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.outboundEnabled").value(true)).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.phoneNumberId").value((Object) null));
        mvc.perform(read(ownerA, tenantA)).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.outboundEnabled").value(true));
        mvc.perform(killSwitch(ownerA, tenantA, "\"1\"", "{\"enabled\":true}")).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));
        assertThat(auditRows(ownerAContext, "OUTBOUND_KILL_SWITCH_CHANGED"))
                .containsExactly(Map.of("enabled", true, "changed_fields", ""));
        assertThat(inContext(ownerAContext, gate::check)).isEqualTo(OutboundGateStatus.INTEGRATION_DISABLED);

        // The configuration route preserves the switch; the switch route touches nothing else.
        mvc.perform(update(ownerA, tenantA, "\"1\"", bodyA(true))).andExpect(status().isOk())
                .andExpect(jsonPath("$.outboundEnabled").value(true));
        mvc.perform(killSwitch(ownerA, tenantA, "\"2\"", "{\"enabled\":false}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.secretRef").value(REF_A));
    }

    @Test
    void authorizesByRoleAndKeepsTwoTenantsApartOnTheSameRoutes() throws Exception {
        mvc.perform(read(viewerA, tenantA)).andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""));
        mvc.perform(put(ROUTE).with(user(viewerA)).with(csrf()).header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isForbidden()).andExpect(problem(403, "Forbidden", "FORBIDDEN"));
        verify(auditRecorder, times(1)).authorizationDenied(eq(TenantPermission.INTEGRATION_MANAGE), any());
        mvc.perform(killSwitch(viewerA, tenantA, "\"0\"", "{\"enabled\":true}"))
                .andExpect(status().isForbidden()).andExpect(problem(403, "Forbidden", "FORBIDDEN"));
        mvc.perform(update(operatorA, tenantA, "\"0\"", bodyA(false)))
                .andExpect(status().isForbidden()).andExpect(problem(403, "Forbidden", "FORBIDDEN"));
        mvc.perform(killSwitch(operatorA, tenantA, "\"0\"", "{\"enabled\":true}"))
                .andExpect(status().isForbidden()).andExpect(problem(403, "Forbidden", "FORBIDDEN"));
        verify(auditRecorder, times(4)).authorizationDenied(eq(TenantPermission.INTEGRATION_MANAGE), any());
        // No tenant header: refused by the tenant filter before the controller, so no problem body is promised.
        mvc.perform(put(ROUTE).with(user(ownerA)).with(csrf()).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(bodyA(false)))
                .andExpect(status().isForbidden());
        // A member of B naming tenant A has no membership there: the tenant filter refuses it before the controller.
        mvc.perform(update(ownerB, tenantA, "\"0\"", bodyA(false))).andExpect(status().isForbidden());
        assertThat(integrationRows(ownerAContext)).isZero();

        mvc.perform(update(ownerA, tenantA, "\"0\"", bodyA(true))).andExpect(status().isOk());
        mvc.perform(killSwitch(ownerA, tenantA, "\"1\"", "{\"enabled\":true}")).andExpect(status().isOk());
        mvc.perform(update(ownerB, tenantB, "\"0\"", body(PHONE_NUMBER_ID_B, BUSINESS_ACCOUNT_ID, "+56 9 2222 0001",
                "CL", REF_B, "true"))).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        mvc.perform(read(ownerA, tenantA)).andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.phoneNumberId").value(PHONE_NUMBER_ID_A))
                .andExpect(jsonPath("$.secretRef").value(REF_A)).andExpect(jsonPath("$.outboundEnabled").value(true));
        mvc.perform(read(ownerB, tenantB)).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.phoneNumberId").value(PHONE_NUMBER_ID_B))
                .andExpect(jsonPath("$.secretRef").value(REF_B)).andExpect(jsonPath("$.outboundEnabled").value(false));
        assertThat(inContext(ownerAContext, gate::check)).isEqualTo(OutboundGateStatus.ENABLED);
        assertThat(inContext(ownerBContext, gate::check)).isEqualTo(OutboundGateStatus.OUTBOUND_DISABLED_FOR_TENANT);
        assertThat(inContext(ownerBContext, () -> repository.findByTenant(tenantA.id().value()))).isEmpty();
        assertThat(resolver.resolve(SecretRef.parse(REF_B))).contains(new SecretValue("test-token-b"));
    }

    @Test
    void theStubIsGoneAndTheTenantGateIsTheOnlyOutboundGate() {
        assertThat(environment.containsProperty("dokene.messaging.outbound-gate.stub.enabled")).isFalse();
        assertThat(applicationContext.getBeansOfType(OutboundGate.class)).hasSize(1);
        assertThat(applicationContext.containsBean("stubOutboundGate")).isFalse();
        assertThat(gate.getClass().getName()).contains("TenantIntegrationOutboundGate");
    }

    private MockHttpServletRequestBuilder read(IdentityId identity, Tenant tenant) {
        return get(ROUTE).with(user(identity)).header("X-Tenant-Id", tenant.id().value());
    }

    private MockHttpServletRequestBuilder update(IdentityId identity, Tenant tenant, String ifMatch, String body) {
        return request(put(ROUTE), identity, tenant, ifMatch, body);
    }

    private MockHttpServletRequestBuilder killSwitch(IdentityId identity, Tenant tenant, String ifMatch,
            String body) {
        return request(put(SWITCH), identity, tenant, ifMatch, body);
    }

    private MockHttpServletRequestBuilder request(MockHttpServletRequestBuilder builder, IdentityId identity,
            Tenant tenant, String ifMatch, String body) {
        builder.with(user(identity)).with(csrf()).header("X-Tenant-Id", tenant.id().value())
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
        return builder;
    }

    private static String bodyA(boolean enabled) {
        return body(PHONE_NUMBER_ID_A, BUSINESS_ACCOUNT_ID, "+56 9 2222 0000", "CL", REF_A, Boolean.toString(enabled));
    }

    /** Null values are omitted, so the body exercises "an omitted nullable field becomes null". */
    private static String body(String phoneNumberId, String businessAccountId, String displayPhoneNumber,
            String displayPhoneRegion, String secretRef, String enabledLiteral) {
        StringBuilder json = new StringBuilder("{");
        String[][] fields = {{"phoneNumberId", phoneNumberId}, {"businessAccountId", businessAccountId},
                {"displayPhoneNumber", displayPhoneNumber}, {"displayPhoneRegion", displayPhoneRegion},
                {"secretRef", secretRef}};
        for (String[] field : fields) {
            if (field[1] != null) {
                json.append('"').append(field[0]).append("\":\"").append(field[1]).append("\",");
            }
        }
        if (enabledLiteral != null) {
            json.append("\"enabled\":").append(enabledLiteral).append(',');
        }
        if (json.charAt(json.length() - 1) == ',') {
            json.setLength(json.length() - 1);
        }
        return json.append('}').toString();
    }

    private static ResultMatcher problem(int status, String title, String code) {
        return result -> {
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            content().json("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\"}"
                    .formatted(title, status, code), JsonCompareMode.STRICT).match(result);
        };
    }

    private List<Map<String, Object>> auditRows(TenantContext context, String eventType) throws Exception {
        return inContext(context, () -> jdbc.query("""
                SELECT enabled, changed_fields FROM dokene.audit_events
                WHERE target_type = 'INTEGRATION' AND target_id = ? AND event_type = ? ORDER BY occurred_at, id
                """, (row, index) -> Map.of("enabled", row.getBoolean("enabled"),
                        "changed_fields", row.getString("changed_fields") == null ? "" : row.getString("changed_fields")),
                context.tenantId().value(), eventType));
    }

    private int integrationRows(TenantContext context) throws Exception {
        return inContext(context, () -> jdbc.queryForObject(
                "SELECT count(*) FROM dokene.tenant_messaging_integrations", Integer.class));
    }

    private int rowCount(TenantContext context, String table, UUID messageId) throws Exception {
        return inContext(context, () -> jdbc.queryForObject(
                "SELECT count(*) FROM dokene." + table + " WHERE message_id = ?", Integer.class, messageId));
    }

    private Seeded seedCustomer(String displayName) throws Exception {
        Instant now = Instant.now();
        // The local provider decides the outcome from the last two digits; 55 is accepted and delivered.
        String number = "8888" + String.format("%02d", Math.abs(UUID.randomUUID().hashCode()) % 100) + "55";
        Customer customer = inContext(ownerAContext, () -> customers.create(displayName, null,
                List.of(new PhoneInput(number, "CR", true))));
        UUID contactId = customer.phones().getFirst().id();
        var policy = inContext(ownerAContext, () -> contacts.get(customer.id()));
        inContext(ownerAContext, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
        inContext(ownerAContext, () -> purchases.record(customer.id(), now.minus(Duration.ofSeconds(60)),
                "Anchor purchase", "anchor-" + UUID.randomUUID()));
        inContext(ownerAContext, () -> followUps.configureCustomer(customer.id(), null,
                LocalDate.now(ZoneId.of("UTC")), 0));
        var snapshot = inContext(ownerAContext, () -> followUps.evaluateSnapshot(customer.id()));
        assertThat(snapshot.evaluation().eligible()).isTrue();
        return new Seeded(customer, contactId, snapshot.policyVersion());
    }

    private UUID approved(Seeded target) throws Exception {
        MvcResult result = mvc.perform(post("/api/customers/{id}/messages", target.customer().id().value())
                        .with(user(ownerA)).with(csrf()).header("X-Tenant-Id", tenantA.id().value())
                        .header("If-Match", "\"" + target.policyVersion() + "\"")
                        .header("Idempotency-Key", "submit-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contactId":"%s","action":"GENERAL_CHECK_IN","templateIntent":"GENERAL_FOLLOW_UP",
                                 "body":"%s","locale":"es-419","origin":"MANUAL"}
                                """.formatted(target.contactId(), BODY)))
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

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }

    private RequestPostProcessor user(IdentityId identityId) {
        var principal = new TestPrincipal(identityId);
        return authentication(new UsernamePasswordAuthenticationToken(principal, "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }

    private record Seeded(Customer customer, UUID contactId, long policyVersion) { }
}
