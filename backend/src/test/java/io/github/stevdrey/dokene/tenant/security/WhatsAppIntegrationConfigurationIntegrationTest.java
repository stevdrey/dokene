// ABOUTME: Proves the tenant WhatsApp integration config API: role matrix, IDOR, RLS, duplicate mapping, secret
// ABOUTME: resolution, stale writes, credential rotation and audit rollback. Uses fake secrets only (issue 134).
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.executeUpdate;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.migrationConnection;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryInt;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.integration.application.IntegrationAuditPort;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
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
import java.util.Set;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class WhatsAppIntegrationConfigurationIntegrationTest {
    private static final java.util.concurrent.atomic.AtomicLong NEXT_ID = new java.util.concurrent.atomic.AtomicLong(
            100_000_000_000_000L + (System.nanoTime() % 1_000_000_000L) * 100);
    private static final String WABA_ID = "200000000000001";

    @TestConfiguration
    static class FakeSecrets {
        /** Fake provider: tenant-scoped keys ending in -ok resolve; everything else is unknown (fail closed). */
        @Bean
        @Primary
        IntegrationSecretResolver fakeResolver() {
            Set<String> known = Set.of("wa-token-ok", "wa-token-two-ok");
            return (scope, reference) -> {
                if (!(scope instanceof IntegrationSecretResolver.Tenant) || !known.contains(reference.value())) {
                    throw new IllegalStateException("unresolved");
                }
                return new ResolvedSecret("fake-secret".toCharArray());
            };
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    MockMvc mvc;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
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
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        write(adminA, tenantA, "\"0\"", body(phoneId, "wa-token-ok", false))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.enabled").value(false));
        write(adminA, tenantA, "\"0\"", body(phoneId, "wa-token-ok", true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        write(adminA, tenantA, "garbage", body(phoneId, "wa-token-ok", true)).andExpect(status().isBadRequest());
        assertThat(auditCount(tenantA)).isEqualTo(2);
    }

    @Test
    void failsClosedOnUnknownSecretsRawCredentialsAndMalformedFields() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "unknown-ref", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
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
    void rotatingTheSecretReferenceBumpsTheCredentialRevisionButDisablingDoesNot() throws Exception {
        write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true)).andExpect(status().isOk());
        assertThat(credentialRevision(tenantA)).isEqualTo(1);

        write(adminA, tenantA, "\"0\"", body(phoneId, "wa-token-ok", false)).andExpect(status().isOk());
        assertThat(credentialRevision(tenantA)).isEqualTo(1);

        write(adminA, tenantA, "\"1\"", body(phoneId, "wa-token-two-ok", false)).andExpect(status().isOk());
        assertThat(credentialRevision(tenantA)).isEqualTo(2);

        // Re-enabling needs a resolvable credential even when the reference did not change.
        executeSql("UPDATE dokene.tenant_messaging_integrations SET secret_ref = 'gone-ref' WHERE tenant_id = ?",
                tenantA.id().value());
        write(adminA, tenantA, "\"2\"", body(phoneId, "gone-ref", true)).andExpect(status().isBadRequest());
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
    void auditFailureRollsBackTheConfigurationChange() throws Exception {
        doThrow(new IllegalStateException("audit down")).when(audit).updated(any(), anyBoolean());
        try {
            write(adminA, tenantA, null, body(phoneId, "wa-token-ok", true));
        } catch (Exception expected) {
            // The request fails; what matters is that nothing was persisted.
        }
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM dokene.tenant_messaging_integrations "
                    + "WHERE tenant_id = ?", tenantA.id().value())).isZero();
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
        }
        return mvc.perform(request);
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

    private void executeSql(String sql, Object... parameters) throws SQLException {
        try (Connection migration = migrationConnection()) {
            executeUpdate(migration, sql, parameters);
        }
    }

    private RequestPostProcessor user(IdentityId identityId) {
        var principal = new TestPrincipal(identityId);
        return authentication(new UsernamePasswordAuthenticationToken(principal, "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
}
