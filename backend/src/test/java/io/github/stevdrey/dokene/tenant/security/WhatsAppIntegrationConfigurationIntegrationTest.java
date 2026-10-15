// ABOUTME: Proves the tenant WhatsApp integration config API: role matrix, IDOR, RLS, duplicate mapping, stale
// ABOUTME: writes, credential rotation, audit rollback and the rejected-mapping throttle (issues 134 and 208).
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.migrationConnection;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryInt;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryUuids;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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

import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.integration.application.IntegrationAuditPort;
import io.github.stevdrey.dokene.integration.application.IntegrationReadiness;
import io.github.stevdrey.dokene.integration.application.IntegrationRefusedException;
import io.github.stevdrey.dokene.integration.application.MappingRejectionLimitProperties;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class WhatsAppIntegrationConfigurationIntegrationTest {
    private static final java.util.concurrent.atomic.AtomicLong NEXT_ID = new java.util.concurrent.atomic.AtomicLong(
            100_000_000_000_000L + (System.nanoTime() % 1_000_000_000L) * 100);
    private static final String WABA_ID = "200000000000001";
    private static final int ACTOR_BUDGET = MappingRejectionLimitProperties.DEFAULT_ACTOR_REJECTIONS;
    private static final int TENANT_BUDGET = MappingRejectionLimitProperties.DEFAULT_TENANT_REJECTIONS;

    /** Runtime readiness that tests can flip without touching the stored integration version. */
    static final class MutableReadiness implements IntegrationReadiness {
        volatile boolean outboundEnabled;

        @Override
        public boolean outboundEnabled(io.github.stevdrey.dokene.tenant.domain.TenantId tenantId) {
            return outboundEnabled;
        }

        @Override
        public boolean webhookVerified(io.github.stevdrey.dokene.tenant.domain.TenantId tenantId) {
            return false;
        }
    }

    @TestConfiguration
    static class ReadinessConfiguration {
        @Bean
        @Primary
        MutableReadiness mutableReadiness() {
            return new MutableReadiness();
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    MockMvc mvc;
    @Autowired MutableReadiness readiness;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired WhatsAppIntegrationRepository repository;
    @MockitoSpyBean IntegrationAuditPort audit;

    /** Phone number ids are globally unique, so every test maps its own. */
    private String phoneId;
    private Tenant tenantA;
    private Tenant tenantB;
    private IdentityId adminA;
    private IdentityId operatorA;
    private IdentityId viewerA;
    private IdentityId adminB;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        readiness.outboundEnabled = false;
        phoneId = Long.toString(NEXT_ID.incrementAndGet());
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, contexts, "Integration A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, contexts, "Integration B " + UUID.randomUUID(), now);
        adminA = member(tenantA, TenantRole.ADMIN);
        operatorA = member(tenantA, TenantRole.OPERATOR);
        viewerA = member(tenantA, TenantRole.VIEWER);
        adminB = member(tenantB, TenantRole.ADMIN);
    }

    @Test
    void roleMatrixLetsOnlyManagersMutateAndChecksPermissionBeforeValidation() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());

        read(operatorA, tenantA).andExpect(status().isOk());
        read(viewerA, tenantA).andExpect(status().isOk());
        write(operatorA, tenantA, "\"0\"", body(phoneId, "wa-token-ok", true))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        write(viewerA, tenantA, "\"0\"", body(phoneId, "wa-token-ok", true)).andExpect(status().isForbidden());
        write(operatorA, tenantA, "\"0\"", "{\"phoneNumberId\":\"not-a-number\"}").andExpect(status().isForbidden());
        mvc.perform(get("/api/integrations/whatsapp").header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsThenUpdatesWithEtagAndRejectsStaleAndMissingPreconditions() throws Exception {
        read(adminA, tenantA).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

        write(adminA, tenantA, "\"0\"", body(phoneId, "wa-token-ok", true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.phoneNumberId").value(phoneId))
                .andExpect(jsonPath("$.displayPhoneNumber").value("+50688887777"))
                .andExpect(jsonPath("$.secretRef").value("wa-token-ok"))
                .andExpect(jsonPath("$.outboundEnabled").value(false))
                .andExpect(jsonPath("$.webhookVerified").value(false))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", false))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        write(adminA, tenantA, "\"0\"", body(phoneId, "wa-token-ok", false))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.enabled").value(false));
        write(adminA, tenantA, "\"0\"", body(phoneId, "wa-token-ok", true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        // Only exactly one strong canonical numeric ETag is accepted (ADR 0023 §4.1).
        for (String malformed : List.of("garbage", "0", "00", "+0", "\"00\"", "\"+0\"", "W/\"1\"", "\"1\",\"2\"",
                "*", "", "\"\"")) {
            write(adminA, tenantA, malformed, body(phoneId, "wa-token-ok", true))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        assertThat(auditCount(tenantA)).isEqualTo(2);
    }

    @Test
    void requiresExactlyOnePreconditionToCreateOrReplace() throws Exception {
        String json = body(phoneId, "wa-token-ok", true);
        var put = put("/api/integrations/whatsapp").with(user(adminA)).with(csrf())
                .header("X-Tenant-Id", tenantA.id().value()).contentType(MediaType.APPLICATION_JSON).content(json);
        mvc.perform(put).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(put("/api/integrations/whatsapp").with(user(adminA)).with(csrf())
                        .header("X-Tenant-Id", tenantA.id().value()).header("If-Match", "\"0\"")
                        .header("If-None-Match", "*").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
        for (String invalid : List.of("\"0\"", "W/\"*\"", "", "*, *")) {
            mvc.perform(put("/api/integrations/whatsapp").with(user(adminA)).with(csrf())
                            .header("X-Tenant-Id", tenantA.id().value()).header("If-None-Match", invalid)
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
        read(adminA, tenantA).andExpect(status().isNotFound());
        write(adminA, tenantA, null, json).andExpect(status().isOk());
        write(adminA, tenantA, null, json).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
    }

    @Test
    void aLostCreateRaceIsAStalePreconditionButAForeignPhoneMappingIsInvalidInput() {
        Instant now = Instant.now();
        String otherPhone = Long.toString(NEXT_ID.incrementAndGet());
        contexts.runWithTenantId(tenantA.id(), () -> {
            repository.insert(WhatsAppIntegration.create(tenantA.id(), phoneId, WABA_ID, null, true, "wa-token-ok", now));
            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(IntegrationRefusedException.class,
                    () -> repository.insert(WhatsAppIntegration.create(tenantA.id(), otherPhone, WABA_ID, null, true,
                            "wa-token-ok", now))).code()).isEqualTo(MessagingErrorCode.STALE_VERSION);
        });
        contexts.runWithTenantId(tenantB.id(), () -> assertThat(
                org.assertj.core.api.Assertions.catchThrowableOfType(IntegrationRefusedException.class,
                        () -> repository.insert(WhatsAppIntegration.create(tenantB.id(), phoneId, WABA_ID, null, true,
                                "wa-token-ok", now))).code()).isEqualTo(MessagingErrorCode.INVALID_INPUT));
    }

    @Test
    void concurrentCreatesFromOneTenantYieldOneWinnerAndAStaleVersion() throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                String phone = i == 0 ? phoneId : Long.toString(NEXT_ID.incrementAndGet());
                futures.add(pool.submit(() -> {
                    barrier.await();
                    var result = write(adminA, tenantA, null, body(phone, "wa-token-ok", true)).andReturn();
                    if (result.getResponse().getStatus() == 409) {
                        assertThat(result.getResponse().getContentAsString()).contains("STALE_VERSION");
                    }
                    return result.getResponse().getStatus();
                }));
            }
            var statuses = new java.util.ArrayList<Integer>();
            for (var future : futures) {
                statuses.add(future.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void rotatingTheCredentialInPlaceBumpsTheRevisionWithoutChangingTheReference() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        assertThat(credentialRevision(tenantA)).isEqualTo(1);

        rotate(adminA, tenantA, "\"0\"").andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.secretRef").value("wa-token-ok"))
                .andExpect(jsonPath("$.enabled").value(true));
        assertThat(credentialRevision(tenantA)).isEqualTo(2);
        assertThat(auditCount(tenantA)).isEqualTo(2);

        // Toggling enabled is not a rotation.
        write(adminA, tenantA, "\"1\"", body(phoneId, "wa-token-ok", false)).andExpect(status().isOk());
        assertThat(credentialRevision(tenantA)).isEqualTo(2);

        rotate(adminA, tenantA, "\"1\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        for (String invalid : List.of("1", "W/\"2\"", "")) {
            rotate(adminA, tenantA, invalid).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/integrations/whatsapp/credential-rotations").with(user(adminA)).with(csrf())
                .header("X-Tenant-Id", tenantA.id().value())).andExpect(status().isBadRequest());
        rotate(operatorA, tenantA, "\"2\"").andExpect(status().isForbidden());
        rotate(viewerA, tenantA, "\"2\"").andExpect(status().isForbidden());
        rotate(adminB, tenantB, "\"0\"").andExpect(status().isNotFound());
    }

    @Test
    void theEtagIsTheConfigurationTokenAndRuntimeReadinessIsNeverServedFromACache() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        read(adminA, tenantA).andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.outboundEnabled").value(false));

        readiness.outboundEnabled = true; // runtime state changes; the stored version does not
        mvc.perform(get("/api/integrations/whatsapp").with(user(adminA)).header("X-Tenant-Id", tenantA.id().value())
                        .header("If-None-Match", "\"0\""))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.outboundEnabled").value(true));
    }

    @Test
    void unverifiedMappingMustNotBeTrustedForRouting() throws Exception {
        // Tenant B pre-registers the number of tenant A (who owns it at Meta) before A configures it.
        write(adminB, tenantB, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        read(adminB, tenantB).andExpect(jsonPath("$.webhookVerified").value(false));

        // A is refused generically and learns nothing about B.
        String refused = write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(refused).doesNotContain(tenantB.id().value().toString());

        // Resolving a tenant from the stored mapping alone would route A's Meta events to B. Nothing stored here is
        // verified, so the webhook (#139) must not resolve tenants from it until ownership is proven, and the send
        // path (#142/#144) must fail closed on webhookVerified=false.
        try (Connection migration = migrationConnection()) {
            assertThat(queryUuids(migration, "SELECT tenant_id FROM dokene.tenant_messaging_integrations "
                    + "WHERE phone_number_id = ?", phoneId)).containsExactly(tenantB.id().value());
        }
        read(adminB, tenantB).andExpect(jsonPath("$.webhookVerified").value(false));
    }

    @Test
    void unsupportedMediaTypesUseTheClosedProblemShape() throws Exception {
        for (MediaType type : List.of(MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM)) {
            mvc.perform(put("/api/integrations/whatsapp").with(user(adminA)).with(csrf())
                            .header("X-Tenant-Id", tenantA.id().value()).contentType(type).content("x"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        mvc.perform(put("/api/integrations/whatsapp").with(user(operatorA)).with(csrf())
                        .header("X-Tenant-Id", tenantA.id().value()).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsWronglyTypedScalarsAndNonObjectBodies() throws Exception {
        for (String json : List.of("null", "[]", "{}",
                "{\"phoneNumberId\":" + phoneId + ",\"businessAccountId\":\"" + WABA_ID
                        + "\",\"enabled\":true,\"secretRef\":\"wa-token-ok\"}",
                "{\"phoneNumberId\":\"" + phoneId + "\",\"businessAccountId\":\"" + WABA_ID
                        + "\",\"enabled\":\"true\",\"secretRef\":\"wa-token-ok\"}")) {
            write(adminA, tenantA, null, json)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        read(adminA, tenantA).andExpect(status().isNotFound());
    }

    @Test
    void rejectsRawCredentialsAndMalformedFields() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "../etc/passwd", true)).andExpect(status().isBadRequest());
        write(adminA, tenantA, null, body("12", "wa-token-ok", true)).andExpect(status().isBadRequest());
        write(adminA, tenantA, null, "{\"phoneNumberId\":\"" + phoneId + "\",\"businessAccountId\":\"" + WABA_ID
                + "\",\"enabled\":true,\"secretRef\":\"wa-token-ok\",\"accessToken\":\"EAAG-raw\"}")
                .andExpect(status().isBadRequest());
        read(adminA, tenantA).andExpect(status().isNotFound());
        assertThat(auditCount(tenantA)).isZero();
    }

    @Test
    void doesNotDiscloseAnotherTenantsMappingOrIntegration() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());

        // B claiming A's phone number looks like any other invalid mapping; B cannot read A's record.
        String duplicate = write(adminB, tenantB, null, body(phoneId, "wa-token-ok", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andReturn().getResponse().getContentAsString();
        String malformed = write(adminB, tenantB, null, body("12", "wa-token-ok", true))
                .andReturn().getResponse().getContentAsString();
        assertThat(duplicate).isEqualTo(malformed);
        read(adminB, tenantB).andExpect(status().isNotFound());
        // A principal of B naming tenant A is not a member there.
        read(adminB, tenantA).andExpect(status().is4xxClientError()).andExpect(content().string(
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(phoneId))));
        read(adminA, tenantA).andExpect(jsonPath("$.phoneNumberId").value(phoneId));

        // B may use a different number; the mapping stays unique per number.
        write(adminB, tenantB, null, body(Long.toString(NEXT_ID.incrementAndGet()), "wa-token-ok", true)).andExpect(status().isOk());
    }

    @Test
    void repeatedForeignMappingsAreThrottledWithTheClosedProblemAndAuditedWithoutTheCandidateId() throws Exception {
        write(adminB, tenantB, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        for (int attempt = 0; attempt < ACTOR_BUDGET; attempt++) {
            write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        var throttled = write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(content().json("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\","
                        + "\"status\":429,\"code\":\"RATE_LIMITED\"}", JsonCompareMode.STRICT))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
                .andReturn().getResponse();
        assertThat(Long.parseLong(throttled.getHeader("Retry-After"))).isLessThanOrEqualTo(15 * 60);
        assertThat(throttled.getContentAsString()).doesNotContain(phoneId);
        assertThat(throttled.getHeaderNames()).allSatisfy(name -> assertThat(throttled.getHeaders(name))
                .noneMatch(value -> value.contains(phoneId)));
        // Every mapping change is gated, so an unmapped candidate cannot be told apart while throttled.
        write(adminA, tenantA, null, body(Long.toString(NEXT_ID.incrementAndGet()), "wa-token-ok", true))
                .andExpect(status().isTooManyRequests());

        // The exhausting rejection and both throttled attempts, attributed to the prober, with no target or id.
        assertThat(rateLimitedDenials(tenantA, adminA)).isEqualTo(3);
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM dokene.audit_events a WHERE a.tenant_id = ? "
                    + "AND row_to_json(a)::text LIKE ?", tenantA.id().value(), "%" + phoneId + "%")).isZero();
        }
        assertThat(auditCount(tenantA)).isZero();

        // Another tenant keeps its own budget.
        write(adminB, tenantB, "\"0\"", body(Long.toString(NEXT_ID.incrementAndGet()), "wa-token-ok", true))
                .andExpect(status().isOk());
        assertThat(rateLimitedDenials(tenantB, adminB)).isZero();
    }

    @Test
    void successfulWritesNeverConsumeBudgetAndKeepingTheStoredNumberIsNeverThrottled() throws Exception {
        String foreign = Long.toString(NEXT_ID.incrementAndGet());
        write(adminB, tenantB, null, body(foreign, "wa-token-ok", true)).andExpect(status().isOk());
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        long version = 0;
        String stored = phoneId;
        for (int remap = 0; remap <= TENANT_BUDGET; remap++, version++) {
            stored = Long.toString(NEXT_ID.incrementAndGet());
            write(adminA, tenantA, "\"" + version + "\"", body(stored, "wa-token-ok", true))
                    .andExpect(status().isOk());
        }

        IdentityId secondAdminA = member(tenantA, TenantRole.ADMIN);
        exhaust(adminA, version, foreign);
        write(adminA, tenantA, "\"" + version + "\"", body(foreign, "wa-token-ok", true))
                .andExpect(status().isTooManyRequests());
        // The kill path: disabling with the stored number cannot probe anything, so it is never throttled.
        write(adminA, tenantA, "\"" + version + "\"", body(stored, "wa-token-ok", false)).andExpect(status().isOk());
        version++;

        // Below the tenant budget another admin is unaffected; once it is exhausted, every actor is throttled.
        exhaust(secondAdminA, version, foreign);
        IdentityId thirdAdminA = member(tenantA, TenantRole.ADMIN);
        write(thirdAdminA, tenantA, "\"" + version + "\"", body(Long.toString(NEXT_ID.incrementAndGet()),
                "wa-token-ok", true)).andExpect(status().isTooManyRequests());
        assertThat(rateLimitedDenials(tenantA, thirdAdminA)).isEqualTo(1);
        read(adminA, tenantA).andExpect(jsonPath("$.phoneNumberId").value(stored))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void throttlingFailsClosedWhenItsDenialCannotBeAuditedAndNeverAuditsInsideTheWrite() throws Exception {
        List<Boolean> auditedInsideATransaction = new CopyOnWriteArrayList<>();
        write(adminB, tenantB, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        for (int attempt = 1; attempt < ACTOR_BUDGET; attempt++) {
            write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isBadRequest());
        }

        doAnswer(invocation -> {
            auditedInsideATransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            throw new AuditPersistenceException();
        }).when(audit).mappingThrottled();
        // Neither the exhausting rejection nor the next throttled attempt answers without its durable denial.
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
        write(adminA, tenantA, null, body(Long.toString(NEXT_ID.incrementAndGet()), "wa-token-ok", true))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));

        assertThat(auditedInsideATransaction).hasSize(2).containsOnly(false);
        assertThat(rateLimitedDenials(tenantA, adminA)).isZero();
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM dokene.tenant_messaging_integrations "
                    + "WHERE tenant_id = ?", tenantA.id().value())).isZero();
        }
    }

    @Test
    void conflictClassificationReliesOnTheseConstraintNames() throws SQLException {
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_constraint WHERE contype = 'u' AND conrelid = "
                    + "'dokene.tenant_messaging_integrations'::regclass AND conname IN (?, ?)",
                    "uq_tenant_messaging_integrations_tenant_provider",
                    "uq_tenant_messaging_integrations_phone_number")).isEqualTo(2);
        }
    }

    @Test
    void malformedNumbersNeverConsumeTheBudget() throws Exception {
        for (int attempt = 0; attempt <= TENANT_BUDGET; attempt++) {
            write(adminA, tenantA, null, body("12", "wa-token-ok", true))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        assertThat(rateLimitedDenials(tenantA, adminA)).isZero();
    }

    @Test
    void rotatingTheSecretReferenceBumpsTheCredentialRevisionButDisablingDoesNot() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        assertThat(credentialRevision(tenantA)).isEqualTo(1);

        write(adminA, tenantA, "\"0\"", body(phoneId, "wa-token-ok", false)).andExpect(status().isOk());
        assertThat(credentialRevision(tenantA)).isEqualTo(1);

        write(adminA, tenantA, "\"1\"", body(phoneId, "wa-token-two-ok", false)).andExpect(status().isOk());
        assertThat(credentialRevision(tenantA)).isEqualTo(2);

        // Only the reference is stored; resolution is the adapter's job at send time, so nothing resolves here.
    }

    @Test
    void rowLevelSecurityHidesRowsWithoutAVerifiedTenantContext() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        try (Connection runtime = runtimeConnectionWithoutContext()) {
            assertThat(queryInt(runtime, "SELECT count(*) FROM dokene.tenant_messaging_integrations")).isZero();
        }
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_policies WHERE tablename = ?",
                    "tenant_messaging_integrations")).isEqualTo(5);
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_class WHERE relname = ? AND relrowsecurity "
                    + "AND relforcerowsecurity", "tenant_messaging_integrations")).isEqualTo(1);
        }
    }

    @Test
    void auditFailureRollsBackTheConfigurationChangeAndAnswersServiceUnavailable() throws Exception {
        doThrow(new AuditPersistenceException()).when(audit).updated(any(), anyBoolean());
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM dokene.tenant_messaging_integrations "
                    + "WHERE tenant_id = ?", tenantA.id().value())).isZero();
        }
        assertThat(auditCount(tenantA)).isZero();
    }

    /** Spends the actor's whole budget remapping onto a number another tenant owns; the version never moves. */
    private void exhaust(IdentityId who, long version, String foreign) throws Exception {
        for (int attempt = 0; attempt < ACTOR_BUDGET; attempt++) {
            write(who, tenantA, "\"" + version + "\"", body(foreign, "wa-token-ok", true))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    private int rateLimitedDenials(Tenant tenant, IdentityId actor) throws SQLException {
        try (Connection migration = migrationConnection()) {
            return queryInt(migration, "SELECT count(*) FROM dokene.audit_events WHERE tenant_id = ? AND actor_id = ? "
                    + "AND event_type = 'AUTHORIZATION_DENIED' AND outcome = 'DENIED' "
                    + "AND denial_reason = 'RATE_LIMITED' AND permission = 'INTEGRATION_MANAGE' "
                    + "AND target_type IS NULL AND target_id IS NULL", tenant.id().value(), actor.value());
        }
    }

    private ResultActions read(IdentityId who, Tenant tenant) throws Exception {
        return mvc.perform(get("/api/integrations/whatsapp").with(user(who))
                .header("X-Tenant-Id", tenant.id().value()));
    }

    private ResultActions write(IdentityId who, Tenant tenant, String ifMatch, String json) throws Exception {
        var request = put("/api/integrations/whatsapp").with(user(who)).with(csrf())
                .header("X-Tenant-Id", tenant.id().value()).contentType(MediaType.APPLICATION_JSON).content(json);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        } else {
            request.header("If-None-Match", "*"); // creation precondition
        }
        return mvc.perform(request);
    }

    private ResultActions rotate(IdentityId who, Tenant tenant, String ifMatch) throws Exception {
        return mvc.perform(post("/api/integrations/whatsapp/credential-rotations").with(user(who)).with(csrf())
                .header("X-Tenant-Id", tenant.id().value()).header("If-Match", ifMatch));
    }

    private static String body(String phoneNumberId, String secretRef, boolean enabled) {
        return "{\"phoneNumberId\":\"" + phoneNumberId + "\",\"businessAccountId\":\"" + WABA_ID
                + "\",\"displayPhoneNumber\":\"+50688887777\",\"enabled\":" + enabled
                + ",\"secretRef\":\"" + secretRef + "\"}";
    }

    private IdentityId member(Tenant tenant, TenantRole role) {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), identity, role, Instant.now());
        return identity;
    }

    private int auditCount(Tenant tenant) throws SQLException {
        try (Connection migration = migrationConnection()) {
            return queryInt(migration, "SELECT count(*) FROM dokene.audit_events WHERE tenant_id = ? "
                    + "AND event_type = 'INTEGRATION_UPDATED'", tenant.id().value());
        }
    }

    private int credentialRevision(Tenant tenant) throws SQLException {
        try (Connection migration = migrationConnection()) {
            return queryInt(migration, "SELECT credential_revision FROM dokene.tenant_messaging_integrations "
                    + "WHERE tenant_id = ?", tenant.id().value());
        }
    }

    private RequestPostProcessor user(IdentityId identityId) {
        var principal = new TestPrincipal(identityId);
        return authentication(new UsernamePasswordAuthenticationToken(principal, "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
}
