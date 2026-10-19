// ABOUTME: Proves the tenant WhatsApp integration config API: role matrix, IDOR, RLS, stale writes, credential rotation,
// ABOUTME: audit rollback, ownership proof, squatted-number reclaim, the proof throttle and the tenant kill switch route.
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
import io.github.stevdrey.dokene.integration.application.IntegrationRefusedException;
import io.github.stevdrey.dokene.integration.application.MappingRejectionLimitProperties;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.Claim;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.NotProven;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.Outcome;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.Proven;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.Unavailable;
import io.github.stevdrey.dokene.integration.application.VerifiedPhoneNumberMappings;
import io.github.stevdrey.dokene.integration.application.WhatsAppOutboundSafetyControl;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.application.OutboundSafetyControl.Blocked;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
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
    private static final String MAPPING_REJECTED = "MAPPING_REJECTED";
    private static final String RATE_LIMITED = "RATE_LIMITED";
    private static final String ON = "{\"enabled\":true}";
    private static final String OFF = "{\"enabled\":false}";

    /** Stands in for the provider adapter: answers per tenant, records every claim it was asked to prove. */
    static final class ScriptedOwnership implements PhoneNumberOwnershipVerifier {
        final Map<TenantId, Outcome> outcomes = new ConcurrentHashMap<>();
        final List<Claim> claims = new CopyOnWriteArrayList<>();
        volatile Runnable beforeAnswer = () -> { };

        void answer(Tenant tenant, Outcome outcome) {
            outcomes.put(tenant.id(), outcome);
        }

        void reset() {
            outcomes.clear();
            claims.clear();
            beforeAnswer = () -> { };
        }

        @Override
        public Outcome verify(Claim claim) {
            claims.add(claim);
            beforeAnswer.run();
            return outcomes.getOrDefault(claim.tenantId(), new NotProven());
        }
    }

    @TestConfiguration
    static class OwnershipConfiguration {
        @Bean
        @Primary
        ScriptedOwnership scriptedOwnership() {
            return new ScriptedOwnership();
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    MockMvc mvc;
    @Autowired ScriptedOwnership ownership;
    @Autowired VerifiedPhoneNumberMappings mappings;
    @Autowired WhatsAppOutboundSafetyControl safety;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired WhatsAppIntegrationRepository repository;
    @MockitoSpyBean IntegrationAuditPort audit;

    /** Verified phone number ids are globally unique, so every test maps its own. */
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
        ownership.reset();
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
    void aLostCreateRaceIsAStalePreconditionAndUnverifiedClaimsOfOneNumberCoexist() throws SQLException {
        Instant now = Instant.now();
        String otherPhone = Long.toString(NEXT_ID.incrementAndGet());
        contexts.runWithTenantId(tenantA.id(), () -> {
            repository.insert(WhatsAppIntegration.create(tenantA.id(), phoneId, WABA_ID, null, true, "wa-token-ok", now));
            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(IntegrationRefusedException.class,
                    () -> repository.insert(WhatsAppIntegration.create(tenantA.id(), otherPhone, WABA_ID, null, true,
                            "wa-token-ok", now))).code()).isEqualTo(MessagingErrorCode.STALE_VERSION);
        });
        // An unverified claim of the same number by another tenant is stored; only a proof can hold the number.
        contexts.runWithTenantId(tenantB.id(), () -> repository.insert(WhatsAppIntegration.create(tenantB.id(), phoneId,
                WABA_ID, null, true, "wa-token-ok", now)));
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM dokene.tenant_messaging_integrations "
                    + "WHERE phone_number_id = ?", phoneId)).isEqualTo(2);
        }
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
    void theTenantOutboundSwitchChangesOnlyThroughItsRouteBumpsTheEtagAndIsAudited() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        read(adminA, tenantA).andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.outboundEnabled").value(false));

        outbound(adminA, tenantA, "\"0\"", ON).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.outboundEnabled").value(true)).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.secretRef").value("wa-token-ok"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        read(adminA, tenantA).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.outboundEnabled").value(true));
        assertThat(switchAudits(tenantA, true)).isEqualTo(1);

        // Stating the stored value is a no op: no write, no version bump, no second audit row.
        outbound(adminA, tenantA, "\"1\"", ON).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.outboundEnabled").value(true));
        assertThat(switchAudits(tenantA, true)).isEqualTo(1);

        // A stale, missing or malformed precondition and a body that is not exactly one JSON boolean change nothing.
        outbound(adminA, tenantA, "\"0\"", OFF).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        outbound(adminA, tenantA, null, OFF).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(put("/api/integrations/whatsapp/outbound").with(user(adminA)).with(csrf())
                        .header("X-Tenant-Id", tenantA.id().value()).header("If-None-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON).content(OFF))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        for (String malformed : List.of("1", "W/\"1\"", "", "*")) {
            outbound(adminA, tenantA, malformed, OFF).andExpect(status().isBadRequest());
        }
        for (String json : List.of("{\"enabled\":false,\"global\":true}", "{\"enabled\":\"true\"}",
                "{\"enabled\":1}", "{\"enabled\":null}", "{}", "null", "[]", "true")) {
            outbound(adminA, tenantA, "\"1\"", json).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        read(adminA, tenantA).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.outboundEnabled").value(true));
        assertThat(switchAudits(tenantA, false)).isZero();

        // The configuration write, the credential rotation and the ownership proof leave the switch as it was.
        write(adminA, tenantA, "\"1\"", body(phoneId, "wa-token-ok", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outboundEnabled").value(true));
        write(adminA, tenantA, "\"2\"", body(phoneId, "wa-token-ok", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outboundEnabled").value(true));
        rotate(adminA, tenantA, "\"3\"").andExpect(status().isOk()).andExpect(jsonPath("$.outboundEnabled").value(true));
        ownership.answer(tenantA, new Proven());
        verify(adminA, tenantA, "\"4\"").andExpect(status().isOk()).andExpect(jsonPath("$.outboundEnabled").value(true))
                .andExpect(jsonPath("$.webhookVerified").value(true));

        // Off again: its own audit row, and the flip never touches the proof.
        outbound(adminA, tenantA, "\"5\"", OFF).andExpect(status().isOk()).andExpect(header().string("ETag", "\"6\""))
                .andExpect(jsonPath("$.outboundEnabled").value(false))
                .andExpect(jsonPath("$.webhookVerified").value(true));
        read(adminA, tenantA).andExpect(jsonPath("$.outboundEnabled").value(false));
        assertThat(switchAudits(tenantA, false)).isEqualTo(1);
        assertThat(switchAudits(tenantA, true)).isEqualTo(1);
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM dokene.audit_events a WHERE a.tenant_id = ? "
                    + "AND a.event_type = 'OUTBOUND_KILL_SWITCH_CHANGED' AND (row_to_json(a)::text LIKE ? "
                    + "OR row_to_json(a)::text LIKE ?)", tenantA.id().value(), "%" + phoneId + "%",
                    "%wa-token%")).isZero();
        }
    }

    @Test
    void theOutboundSwitchIsIndependentOfTheEnabledFlagAndTheProof() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", false)).andExpect(status().isOk());
        outbound(adminA, tenantA, "\"0\"", ON).andExpect(status().isOk())
                .andExpect(jsonPath("$.outboundEnabled").value(true)).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.webhookVerified").value(false));
        // Switched on, but still not a sender: the integration gate answers after the switches.
        assertThat(integrationGate(tenantA)).contains(new Blocked(MessagingErrorCode.INTEGRATION_DISABLED));
    }

    @Test
    void onlyManagersOfTheSelectedTenantFlipTheOutboundSwitch() throws Exception {
        outbound(adminA, tenantA, "\"0\"", ON).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        write(adminB, tenantB, null, body(Long.toString(NEXT_ID.incrementAndGet()), "wa-token-b", true))
                .andExpect(status().isOk());
        outbound(adminB, tenantB, "\"0\"", ON).andExpect(status().isOk());

        // P3-KILL-05 / P3-PERM-12: OPERATOR and VIEWER are denied both ways, before the input is looked at.
        for (String json : List.of(ON, OFF)) {
            outbound(operatorA, tenantA, "\"0\"", json).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            outbound(viewerA, tenantA, "\"0\"", json).andExpect(status().isForbidden());
        }
        outbound(operatorA, tenantA, "garbage", "{}").andExpect(status().isForbidden());
        // P3-KILL-06: an admin of A naming tenant B is not a member there.
        outbound(adminA, tenantB, "\"1\"", OFF).andExpect(status().isForbidden());
        // No CSRF token, no change.
        mvc.perform(put("/api/integrations/whatsapp/outbound").with(user(adminA))
                        .header("X-Tenant-Id", tenantA.id().value()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(ON))
                .andExpect(status().isForbidden());

        read(adminA, tenantA).andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.outboundEnabled").value(false));
        read(adminB, tenantB).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.outboundEnabled").value(true));
        assertThat(switchAudits(tenantA, true) + switchAudits(tenantA, false)).isZero();
        assertThat(switchAudits(tenantB, true)).isEqualTo(1);
    }

    @Test
    void anOutboundSwitchFlipRollsBackWhenItsAuditFails() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        doThrow(new AuditPersistenceException()).when(audit).outboundSwitchChanged(any(), anyBoolean());
        outbound(adminA, tenantA, "\"0\"", ON).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(""));
        read(adminA, tenantA).andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.outboundEnabled").value(false));
        assertThat(switchAudits(tenantA, true)).isZero();
    }

    @Test
    void aPreRegisteredNumberNeverRoutesToTheSquatterAndTheOwnerReclaimsItByProvingOwnership() throws Exception {
        // Tenant B pre-registers the number of tenant A (who owns it at Meta) before A configures it.
        write(adminB, tenantB, null, body(phoneId, "wa-token-b", true)).andExpect(status().isOk());

        // A is neither blocked nor told anything about B.
        String created = write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.webhookVerified").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain(tenantB.id().value().toString()).doesNotContain("wa-token-b");

        // No unverified claim routes events or may send.
        assertThat(mappings.tenantFor(phoneId)).isEmpty();
        assertThat(integrationGate(tenantA)).contains(new Blocked(MessagingErrorCode.INTEGRATION_DISABLED));
        assertThat(integrationGate(tenantB)).contains(new Blocked(MessagingErrorCode.INTEGRATION_DISABLED));

        // B's credential does not reach the number, so B cannot verify it.
        ownership.answer(tenantB, new NotProven());
        String notProven = verify(adminB, tenantB, "\"0\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT")).andReturn().getResponse().getContentAsString();
        assertThat(mappings.tenantFor(phoneId)).isEmpty();

        // A proves ownership with its own credential; from then on only A is resolved for the number.
        ownership.answer(tenantA, new Proven());
        verify(adminA, tenantA, "\"0\"").andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.webhookVerified").value(true));
        read(adminA, tenantA).andExpect(jsonPath("$.webhookVerified").value(true));
        assertThat(mappings.tenantFor(phoneId)).contains(tenantA.id());
        assertThat(integrationGate(tenantA)).isEmpty();
        assertThat(integrationGate(tenantB)).contains(new Blocked(MessagingErrorCode.INTEGRATION_DISABLED));
        // The global kill switch (#144) still blocks every send first.
        assertThat(contexts.callWithTenantId(tenantA.id(), () -> safety.evaluate(tenantA.id())))
                .isEqualTo(new Blocked(MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY));

        // Even a credential that reaches the number cannot take over a verified mapping, and B cannot tell why.
        ownership.answer(tenantB, new Proven());
        String taken = verify(adminB, tenantB, "\"0\"").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(taken).isEqualTo(notProven).doesNotContain(tenantA.id().value().toString())
                .doesNotContain(phoneId);
        read(adminB, tenantB).andExpect(jsonPath("$.webhookVerified").value(false));
        assertThat(mappings.tenantFor(phoneId)).contains(tenantA.id());

        // Each proof used only the asking tenant's own configuration and credential reference.
        assertThat(ownership.claims).containsExactly(
                new Claim(tenantB.id(), phoneId, WABA_ID, "wa-token-b"),
                new Claim(tenantA.id(), phoneId, WABA_ID, "wa-token-ok"),
                new Claim(tenantB.id(), phoneId, WABA_ID, "wa-token-b"));

        // Disabling clears the proof, so a disabled owner never locks the number: routing and sending stop at once.
        write(adminA, tenantA, "\"1\"", body(phoneId, "wa-token-ok", false))
                .andExpect(jsonPath("$.webhookVerified").value(false));
        assertThat(mappings.tenantFor(phoneId)).isEmpty();
        assertThat(integrationGate(tenantA)).contains(new Blocked(MessagingErrorCode.INTEGRATION_DISABLED));
        // Re-enabling does not bring the proof back; the owner verifies again.
        write(adminA, tenantA, "\"2\"", body(phoneId, "wa-token-ok", true))
                .andExpect(jsonPath("$.webhookVerified").value(false));
        assertThat(mappings.tenantFor(phoneId)).isEmpty();
        verify(adminA, tenantA, "\"3\"").andExpect(jsonPath("$.webhookVerified").value(true));
        assertThat(mappings.tenantFor(phoneId)).contains(tenantA.id());
    }

    @Test
    void aNumberReleasedByDisablingCanBeVerifiedByAnotherTenantThatReachesIt() throws Exception {
        ownership.answer(tenantA, new Proven());
        ownership.answer(tenantB, new Proven());
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        verify(adminA, tenantA, "\"0\"").andExpect(status().isOk());
        write(adminB, tenantB, null, body(phoneId, "wa-token-b", true)).andExpect(status().isOk());
        verify(adminB, tenantB, "\"0\"").andExpect(status().isBadRequest());

        // A disables (for example after the number moved at Meta); B's credential now proves it and B takes over.
        write(adminA, tenantA, "\"1\"", body(phoneId, "wa-token-ok", false)).andExpect(status().isOk());
        verify(adminB, tenantB, "\"0\"").andExpect(status().isOk()).andExpect(jsonPath("$.webhookVerified").value(true));
        assertThat(mappings.tenantFor(phoneId)).contains(tenantB.id());

        // A re-enables later: its configuration is kept, but it cannot take the number back while B holds it.
        write(adminA, tenantA, "\"2\"", body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        verify(adminA, tenantA, "\"3\"").andExpect(status().isBadRequest());
        assertThat(mappings.tenantFor(phoneId)).contains(tenantB.id());
    }

    @Test
    void changingTheNumberTheBusinessAccountOrTheCredentialRequiresANewProof() throws Exception {
        String otherPhone = Long.toString(NEXT_ID.incrementAndGet());
        ownership.answer(tenantA, new Proven());
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        verify(adminA, tenantA, "\"0\"").andExpect(jsonPath("$.webhookVerified").value(true));

        // The display number is not part of the proof.
        write(adminA, tenantA, "\"1\"", body(phoneId, WABA_ID, null, "wa-token-ok", true))
                .andExpect(jsonPath("$.webhookVerified").value(true));
        rotate(adminA, tenantA, "\"2\"").andExpect(jsonPath("$.webhookVerified").value(false));
        assertThat(mappings.tenantFor(phoneId)).isEmpty();
        verify(adminA, tenantA, "\"3\"").andExpect(jsonPath("$.webhookVerified").value(true));
        write(adminA, tenantA, "\"4\"", body(phoneId, "wa-token-two-ok", true))
                .andExpect(jsonPath("$.webhookVerified").value(false));
        verify(adminA, tenantA, "\"5\"").andExpect(jsonPath("$.webhookVerified").value(true));
        write(adminA, tenantA, "\"6\"", body(phoneId, "200000000000002", "+50688887777", "wa-token-two-ok", true))
                .andExpect(jsonPath("$.webhookVerified").value(false));
        verify(adminA, tenantA, "\"7\"").andExpect(jsonPath("$.webhookVerified").value(true));
        write(adminA, tenantA, "\"8\"", body(otherPhone, "wa-token-two-ok", true))
                .andExpect(jsonPath("$.webhookVerified").value(false));
        assertThat(mappings.tenantFor(phoneId)).isEmpty();
        assertThat(mappings.tenantFor(otherPhone)).isEmpty();
        verify(adminA, tenantA, "\"9\"").andExpect(jsonPath("$.webhookVerified").value(true));
        assertThat(mappings.tenantFor(otherPhone)).contains(tenantA.id());

        // The database refuses any write that keeps a proof for a configuration it was not made for, and a new row that
        // claims to be verified, even when it bypasses the domain.
        try (Connection migration = migrationConnection()) {
            for (String change : List.of("credential_revision = credential_revision + 1",
                    "phone_number_id = '" + NEXT_ID.incrementAndGet() + "'",
                    "business_account_id = '200000000000009'", "enabled = false")) {
                try (var update = migration.prepareStatement("UPDATE dokene.tenant_messaging_integrations SET "
                        + change + " WHERE tenant_id = ?")) {
                    update.setObject(1, tenantA.id().value());
                    assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(SQLException.class,
                            update::executeUpdate).getMessage())
                            .contains("WhatsApp ownership proof does not match the integration");
                }
            }
            try (var insert = migration.prepareStatement("INSERT INTO dokene.tenant_messaging_integrations (id, "
                    + "tenant_id, provider, phone_number_id, business_account_id, enabled, secret_ref, created_at, "
                    + "updated_at, ownership_verified_at) VALUES (?, ?, 'META_WHATSAPP', ?, ?, true, 'wa-token-ok', "
                    + "now(), now(), now())")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, tenantB.id().value());
                insert.setString(3, Long.toString(NEXT_ID.incrementAndGet()));
                insert.setString(4, WABA_ID);
                assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(SQLException.class,
                        insert::executeUpdate).getMessage())
                        .contains("WhatsApp ownership proof does not match the integration");
            }
            // Clearing the proof in the same statement is always allowed.
            assertThat(queryInt(migration, "WITH changed AS (UPDATE dokene.tenant_messaging_integrations "
                    + "SET enabled = false, ownership_verified_at = NULL WHERE tenant_id = ? RETURNING 1) "
                    + "SELECT count(*) FROM changed", tenantA.id().value())).isEqualTo(1);
        }
    }

    @Test
    void onlyManagersVerifyOwnershipAndOnlyWithAStrongIfMatch() throws Exception {
        ownership.answer(tenantA, new Proven());
        verify(adminA, tenantA, "\"0\"").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());

        verify(operatorA, tenantA, "\"0\"").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(viewerA, tenantA, "\"0\"").andExpect(status().isForbidden());
        for (String invalid : List.of("0", "W/\"0\"", "", "*")) {
            verify(adminA, tenantA, invalid).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verify(adminA, tenantA, null).andExpect(status().isBadRequest());
        mvc.perform(post("/api/integrations/whatsapp/ownership-verifications").with(user(adminA)).with(csrf())
                .header("X-Tenant-Id", tenantA.id().value()).header("If-None-Match", "*"))
                .andExpect(status().isBadRequest());
        verify(adminA, tenantA, "\"1\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        // A refused request never reaches the provider.
        assertThat(ownership.claims).isEmpty();

        verify(adminA, tenantA, "\"0\"").andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.webhookVerified").value(true));
        assertThat(ownership.claims).containsExactly(new Claim(tenantA.id(), phoneId, WABA_ID, "wa-token-ok"));
        assertThat(auditCount(tenantA)).isEqualTo(2);
        verify(adminB, tenantB, "\"0\"").andExpect(status().isNotFound());

        // A disabled integration cannot be verified: refused deterministically, without the provider or any budget.
        write(adminA, tenantA, "\"1\"", body(phoneId, "wa-token-ok", false)).andExpect(status().isOk());
        verify(adminA, tenantA, "\"2\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(ownership.claims).hasSize(1);
        assertThat(denials(tenantA, adminA, MAPPING_REJECTED)).isZero();
    }

    @Test
    void aConfigurationChangeDuringTheProofIsStaleAndRecordsNothing() throws Exception {
        ownership.answer(tenantA, new Proven());
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        ownership.beforeAnswer = () -> {
            try (Connection migration = migrationConnection();
                    var change = migration.prepareStatement("UPDATE dokene.tenant_messaging_integrations "
                            + "SET version = version + 1 WHERE tenant_id = ?")) {
                change.setObject(1, tenantA.id().value());
                change.executeUpdate();
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        };
        verify(adminA, tenantA, "\"0\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        read(adminA, tenantA).andExpect(jsonPath("$.webhookVerified").value(false));
        assertThat(mappings.tenantFor(phoneId)).isEmpty();
        assertThat(denials(tenantA, adminA, MAPPING_REJECTED)).isZero();
    }

    @Test
    void aProofIsNotRecordedWhenItsAuditFails() throws Exception {
        ownership.answer(tenantA, new Proven());
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        doThrow(new AuditPersistenceException()).when(audit).updated(any(), anyBoolean());
        verify(adminA, tenantA, "\"0\"").andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
        assertThat(mappings.tenantFor(phoneId)).isEmpty();
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM dokene.tenant_messaging_integrations "
                    + "WHERE tenant_id = ? AND ownership_verified_at IS NULL AND version = 0",
                    tenantA.id().value())).isEqualTo(1);
        }
    }

    @Test
    void theVerifiedMappingResolverIsTheOnlyCrossTenantReadAndExposesATenantIdOnly() throws Exception {
        for (String malformed : java.util.Arrays.asList(null, "12", "' OR 1=1 --", phoneId + "0".repeat(40))) {
            assertThat(mappings.tenantFor(malformed)).isEmpty();
        }
        try (Connection runtime = runtimeConnectionWithoutContext();
                var resolve = runtime.prepareStatement("SELECT dokene.resolve_verified_whatsapp_tenant(?)")) {
            resolve.setString(1, phoneId);
            try (var result = resolve.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject(1)).isNull();
            }
        }
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_proc WHERE proname = ? AND prosecdef "
                    + "AND prorettype = 'uuid'::regtype", "resolve_verified_whatsapp_tenant")).isEqualTo(1);
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_proc p WHERE p.proname = ? "
                    + "AND has_function_privilege('public', p.oid, 'EXECUTE')",
                    "resolve_verified_whatsapp_tenant")).isZero();
        }
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

        // B naming A's number is stored as B's own unverified claim; it neither fails nor reveals A.
        String claimed = write(adminB, tenantB, null, body(phoneId, "wa-token-b", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.webhookVerified").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(claimed).doesNotContain(tenantA.id().value().toString()).doesNotContain("wa-token-ok");
        read(adminB, tenantB).andExpect(jsonPath("$.secretRef").value("wa-token-b"));
        // A principal of B naming tenant A is not a member there.
        read(adminB, tenantA).andExpect(status().is4xxClientError()).andExpect(content().string(
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(phoneId))));
        read(adminA, tenantA).andExpect(jsonPath("$.phoneNumberId").value(phoneId))
                .andExpect(jsonPath("$.secretRef").value("wa-token-ok"));
    }
    @Test
    void repeatedFailedProofsAreThrottledWithTheClosedProblemAndAuditedWithoutTheCandidateId() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        for (int attempt = 1; attempt <= ACTOR_BUDGET; attempt++) {
            verify(adminA, tenantA, "\"0\"")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            assertThat(denials(tenantA, adminA, MAPPING_REJECTED)).isEqualTo(attempt);
        }
        assertThat(denials(tenantA, adminA, RATE_LIMITED)).isZero();

        int asked = ownership.claims.size();
        var throttled = verify(adminA, tenantA, "\"0\"")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(content().json("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\","
                        + "\"status\":429,\"code\":\"RATE_LIMITED\"}", JsonCompareMode.STRICT))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
                .andReturn().getResponse();
        assertThat(ownership.claims).hasSize(asked); // a throttled proof never reaches the provider
        assertThat(Long.parseLong(throttled.getHeader("Retry-After"))).isLessThanOrEqualTo(15 * 60);
        assertThat(throttled.getContentAsString()).doesNotContain(phoneId);
        assertThat(throttled.getHeaderNames()).allSatisfy(name -> assertThat(throttled.getHeaders(name))
                .noneMatch(value -> value.contains(phoneId)));

        // Configuration writes are never throttled; every proof is, whatever number it is for.
        write(adminA, tenantA, "\"0\"", body(Long.toString(NEXT_ID.incrementAndGet()), "wa-token-ok", true))
                .andExpect(status().isOk());
        verify(adminA, tenantA, "\"1\"").andExpect(status().isTooManyRequests());

        // One denial per refused attempt, attributed to the prober, with no target or id.
        assertThat(denials(tenantA, adminA, MAPPING_REJECTED)).isEqualTo(ACTOR_BUDGET);
        assertThat(denials(tenantA, adminA, RATE_LIMITED)).isEqualTo(2);
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM dokene.audit_events a WHERE a.tenant_id = ? "
                    + "AND row_to_json(a)::text LIKE ?", tenantA.id().value(), "%" + phoneId + "%")).isZero();
        }
        assertThat(auditCount(tenantA)).isEqualTo(2);

        // Another tenant keeps its own budget.
        ownership.answer(tenantB, new Proven());
        write(adminB, tenantB, null, body(Long.toString(NEXT_ID.incrementAndGet()), "wa-token-ok", true))
                .andExpect(status().isOk());
        verify(adminB, tenantB, "\"0\"").andExpect(status().isOk());
        assertThat(denials(tenantB, adminB, MAPPING_REJECTED)).isZero();
        assertThat(denials(tenantB, adminB, RATE_LIMITED)).isZero();
    }
    @Test
    void successfulProofsNeverConsumeBudgetAndTheTenantBudgetSpansActors() throws Exception {
        ownership.answer(tenantA, new Proven());
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        long version = 0;
        for (int proof = 0; proof <= TENANT_BUDGET; proof++, version++) {
            verify(adminA, tenantA, "\"" + version + "\"").andExpect(status().isOk());
        }

        ownership.answer(tenantA, new NotProven());
        IdentityId secondAdminA = member(tenantA, TenantRole.ADMIN);
        exhaust(adminA, version);
        verify(adminA, tenantA, "\"" + version + "\"").andExpect(status().isTooManyRequests());
        // The kill path: disabling (and re-enabling) is a configuration write, so it is never throttled.
        write(adminA, tenantA, "\"" + version + "\"", body(phoneId, "wa-token-ok", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookVerified").value(false));
        version++;
        write(adminA, tenantA, "\"" + version + "\"", body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        version++;

        // Below the tenant budget another admin is unaffected; once it is exhausted, every actor is throttled.
        exhaust(secondAdminA, version);
        IdentityId thirdAdminA = member(tenantA, TenantRole.ADMIN);
        verify(thirdAdminA, tenantA, "\"" + version + "\"").andExpect(status().isTooManyRequests());
        assertThat(denials(tenantA, adminA, MAPPING_REJECTED)).isEqualTo(ACTOR_BUDGET);
        assertThat(denials(tenantA, secondAdminA, MAPPING_REJECTED)).isEqualTo(ACTOR_BUDGET);
        assertThat(denials(tenantA, adminA, RATE_LIMITED)).isEqualTo(1);
        assertThat(denials(tenantA, thirdAdminA, RATE_LIMITED)).isEqualTo(1);
        // Disabling cleared the proof; failed proofs never recorded a new one.
        read(adminA, tenantA).andExpect(jsonPath("$.webhookVerified").value(false))
                .andExpect(jsonPath("$.enabled").value(true));
    }
    @Test
    void refusalsFailClosedWhenTheirDenialCannotBeAuditedAndNeverAuditInsideTheWrite() throws Exception {
        List<Boolean> auditedInsideATransaction = new CopyOnWriteArrayList<>();
        AtomicBoolean auditFails = new AtomicBoolean(true);
        Answer<Object> recording = invocation -> {
            auditedInsideATransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (auditFails.get()) {
                throw new AuditPersistenceException();
            }
            return invocation.callRealMethod();
        };
        doAnswer(recording).when(audit).mappingRejected();
        doAnswer(recording).when(audit).mappingThrottled();
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());

        // A rejected proof whose denial cannot be recorded is not answered; it still counts (fail closed).
        verify(adminA, tenantA, "\"0\"").andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
        assertThat(denials(tenantA, adminA, MAPPING_REJECTED)).isZero();

        auditFails.set(false);
        for (int attempt = 1; attempt < ACTOR_BUDGET; attempt++) {
            verify(adminA, tenantA, "\"0\"").andExpect(status().isBadRequest());
        }
        assertThat(denials(tenantA, adminA, MAPPING_REJECTED)).isEqualTo(ACTOR_BUDGET - 1);

        // A conflict with another tenant's verified mapping is audited after its write rolled back.
        String phoneOfB = Long.toString(NEXT_ID.incrementAndGet());
        ownership.answer(tenantB, new Proven());
        write(adminB, tenantB, null, body(phoneOfB, "wa-token-ok", true)).andExpect(status().isOk());
        verify(adminB, tenantB, "\"0\"").andExpect(status().isOk());
        IdentityId secondAdminA = member(tenantA, TenantRole.ADMIN);
        ownership.answer(tenantA, new Proven());
        write(adminA, tenantA, "\"0\"", body(phoneOfB, "wa-token-ok", true)).andExpect(status().isOk());
        verify(secondAdminA, tenantA, "\"1\"").andExpect(status().isBadRequest());

        auditFails.set(true);
        verify(adminA, tenantA, "\"1\"").andExpect(status().isServiceUnavailable()).andExpect(content().string(""));

        assertThat(auditedInsideATransaction).hasSize(ACTOR_BUDGET + 2).containsOnly(false);
        assertThat(denials(tenantA, adminA, RATE_LIMITED)).isZero();
        read(adminA, tenantA).andExpect(jsonPath("$.webhookVerified").value(false));
    }
    @Test
    void conflictClassificationReliesOnTheseConstraintNames() throws SQLException {
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_constraint WHERE contype = 'u' AND conrelid = "
                    + "'dokene.tenant_messaging_integrations'::regclass AND conname = ?",
                    "uq_tenant_messaging_integrations_tenant_provider")).isEqualTo(1);
            // The number is unique among verified mappings only; the former global key is gone.
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_constraint WHERE conname = ?",
                    "uq_tenant_messaging_integrations_phone_number")).isZero();
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_indexes WHERE schemaname = 'dokene' "
                    + "AND indexname = ? AND indexdef LIKE 'CREATE UNIQUE INDEX%' "
                    + "AND indexdef LIKE '%WHERE (ownership_verified_at IS NOT NULL)%'",
                    "uq_tenant_messaging_integrations_verified_phone_number")).isEqualTo(1);
        }
    }
    @Test
    void inconclusiveProofsAndConfigurationWritesNeverConsumeTheBudget() throws Exception {
        for (int attempt = 0; attempt <= TENANT_BUDGET; attempt++) {
            write(adminA, tenantA, null, body("12", "wa-token-ok", true))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        ownership.answer(tenantA, new Unavailable());
        for (int attempt = 0; attempt <= TENANT_BUDGET; attempt++) {
            verify(adminA, tenantA, "\"0\"").andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
        }
        long version = 0;
        for (int remap = 0; remap <= TENANT_BUDGET; remap++, version++) {
            write(adminA, tenantA, "\"" + version + "\"", body(Long.toString(NEXT_ID.incrementAndGet()),
                    "wa-token-ok", true)).andExpect(status().isOk());
        }
        ownership.answer(tenantA, new Proven());
        verify(adminA, tenantA, "\"" + version + "\"").andExpect(status().isOk());
        assertThat(denials(tenantA, adminA, MAPPING_REJECTED)).isZero();
        assertThat(denials(tenantA, adminA, RATE_LIMITED)).isZero();
    }
    @Test
    void rotatingTheSecretReferenceBumpsTheCredentialRevisionButDisablingDoesNot() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        assertThat(credentialRevision(tenantA)).isEqualTo(1);

        write(adminA, tenantA, "\"0\"", body(phoneId, "wa-token-ok", false)).andExpect(status().isOk());
        assertThat(credentialRevision(tenantA)).isEqualTo(1);

        write(adminA, tenantA, "\"1\"", body(phoneId, "wa-token-two-ok", false)).andExpect(status().isOk());
        assertThat(credentialRevision(tenantA)).isEqualTo(2);

        // Only the reference is stored; resolution is the adapter's job, so writing configuration resolves nothing.
        assertThat(ownership.claims).isEmpty();
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

    /** Spends the actor's whole budget on proofs the provider refuses; the version never moves. */
    private void exhaust(IdentityId who, long version) throws Exception {
        for (int attempt = 0; attempt < ACTOR_BUDGET; attempt++) {
            verify(who, tenantA, "\"" + version + "\"")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    private Optional<Blocked> integrationGate(Tenant tenant) {
        return contexts.callWithTenantId(tenant.id(), () -> safety.integrationGate(tenant.id()));
    }

    private int denials(Tenant tenant, IdentityId actor, String reason) throws SQLException {
        try (Connection migration = migrationConnection()) {
            return queryInt(migration, "SELECT count(*) FROM dokene.audit_events WHERE tenant_id = ? AND actor_id = ? "
                    + "AND event_type = 'AUTHORIZATION_DENIED' AND outcome = 'DENIED' "
                    + "AND denial_reason = ? AND permission = 'INTEGRATION_MANAGE' "
                    + "AND target_type IS NULL AND target_id IS NULL", tenant.id().value(), actor.value(), reason);
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

    private ResultActions outbound(IdentityId who, Tenant tenant, String ifMatch, String json) throws Exception {
        var request = put("/api/integrations/whatsapp/outbound").with(user(who)).with(csrf())
                .header("X-Tenant-Id", tenant.id().value()).contentType(MediaType.APPLICATION_JSON).content(json);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }

    private int switchAudits(Tenant tenant, boolean enabled) throws SQLException {
        try (Connection migration = migrationConnection()) {
            return queryInt(migration, "SELECT count(*) FROM dokene.audit_events WHERE tenant_id = ? "
                    + "AND event_type = 'OUTBOUND_KILL_SWITCH_CHANGED' AND outcome = 'SUCCESS' "
                    + "AND target_type = 'INTEGRATION' AND enabled = ?", tenant.id().value(), enabled);
        }
    }

    private ResultActions verify(IdentityId who, Tenant tenant, String ifMatch) throws Exception {
        var request = post("/api/integrations/whatsapp/ownership-verifications").with(user(who)).with(csrf())
                .header("X-Tenant-Id", tenant.id().value());
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }

    private static String body(String phoneNumberId, String secretRef, boolean enabled) {
        return body(phoneNumberId, WABA_ID, "+50688887777", secretRef, enabled);
    }

    private static String body(String phoneNumberId, String businessAccountId, String displayPhoneNumber,
            String secretRef, boolean enabled) {
        return "{\"phoneNumberId\":\"" + phoneNumberId + "\",\"businessAccountId\":\"" + businessAccountId
                + "\"" + (displayPhoneNumber == null ? "" : ",\"displayPhoneNumber\":\"" + displayPhoneNumber + "\"")
                + ",\"enabled\":" + enabled + ",\"secretRef\":\"" + secretRef + "\"}";
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
