// ABOUTME: Proves the tenant template registry: role matrix, strict bodies, provider-only approval evidence, stale and
// ABOUTME: changed mappings becoming ineligible, tenant isolation, metadata-only audit and rollback (issue 135).
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.migrationConnection;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryInt;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.template.application.TemplateAuditPort;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver;
import io.github.stevdrey.dokene.template.application.TemplateProviderGateway;
import io.github.stevdrey.dokene.template.domain.TemplateApprovalStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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

@SpringBootTest(properties = "dokene.ai.provider=fake")
class TemplateRegistryIntegrationTest {
    private static final AtomicLong NEXT_ID = new AtomicLong(
            300_000_000_000_000L + (System.nanoTime() % 1_000_000_000L) * 100);
    private static final String WABA_ID = "400000000000001";
    private static final String TEMPLATE = "{\"providerTemplateName\":\"repeat_purchase_v1\",\"language\":\"es_MX\","
            + "\"parameterNames\":[\"customer_name\",\"product\"],\"enabled\":true}";

    /** Stands in for Meta: the test chooses what the provider reports; the sender is the real stored integration. */
    static final class ScriptedGateway implements TemplateProviderGateway {
        private final WhatsAppIntegrationRepository integrations;
        private final TenantContextProvider contexts;
        volatile Lookup next = new Unavailable();
        /** Runs inside lookup, before it answers: lets a test land a newer refresh while this one is in flight. */
        volatile Runnable duringLookup = () -> { };

        ScriptedGateway(WhatsAppIntegrationRepository integrations, TenantContextProvider contexts) {
            this.integrations = integrations;
            this.contexts = contexts;
        }

        @Override
        public Optional<Sender> currentSender() {
            return integrations.findByTenant(contexts.requireCurrent().tenantId())
                    .map(i -> new Sender(i.id(), i.businessAccountId()));
        }

        @Override
        public Lookup lookup(String providerTemplateName, String language) {
            Lookup answer = next;
            duringLookup.run();
            return answer;
        }
    }

    @TestConfiguration
    static class GatewayConfiguration {
        @Bean
        @Primary
        ScriptedGateway scriptedGateway(WhatsAppIntegrationRepository integrations, TenantContextProvider contexts) {
            return new ScriptedGateway(integrations, contexts);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    MockMvc mvc;
    @Autowired ScriptedGateway gateway;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired WhatsAppIntegrationRepository integrations;
    @Autowired TemplateMappingResolver resolver;
    @MockitoSpyBean TemplateAuditPort audit;

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
        gateway.next = new TemplateProviderGateway.Unavailable();
        gateway.duringLookup = () -> { };
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, contexts, "Template A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, contexts, "Template B " + UUID.randomUUID(), now);
        adminA = member(tenantA, TenantRole.ADMIN);
        operatorA = member(tenantA, TenantRole.OPERATOR);
        viewerA = member(tenantA, TenantRole.VIEWER);
        adminB = member(tenantB, TenantRole.ADMIN);
        seedIntegration(tenantA);
        seedIntegration(tenantB);
    }

    @Test
    void roleMatrixLetsViewersReadOnlyAndChecksPermissionBeforeValidation() throws Exception {
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk());

        list(viewerA, tenantA).andExpect(status().isOk());
        list(operatorA, tenantA).andExpect(status().isOk());
        create(operatorA, tenantA, "RELATED_PRODUCT", TEMPLATE).andExpect(status().isOk());
        create(viewerA, tenantA, "SEASONAL_EVENT", TEMPLATE).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        create(viewerA, tenantA, "SEASONAL_EVENT", "{\"nonsense\":1}").andExpect(status().isForbidden());
        refresh(viewerA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isForbidden());
        mvc.perform(get("/api/templates").header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void templateWritesNeverTouchTheIntegrationConfiguration() throws Exception {
        int before = integrationVersion(tenantA);
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk());
        assertThat(integrationVersion(tenantA)).isEqualTo(before);
        mvc.perform(get("/api/integrations/whatsapp").with(user(viewerA)).header("X-Tenant-Id", tenantA.id().value()))
                .andExpect(status().isOk());
        // Template roles grant no integration management: a viewer cannot write the integration either.
        mvc.perform(put("/api/integrations/whatsapp").with(user(viewerA)).with(csrf())
                .header("X-Tenant-Id", tenantA.id().value()).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void listsOneSlotPerIntentAndRejectsUnknownIntentsAndModelSuppliedIds() throws Exception {
        list(adminA, tenantA).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.items.length()").value(SemanticTemplateIntent.values().length))
                .andExpect(jsonPath("$.items[0].mapped").value(false))
                .andExpect(jsonPath("$.items[0].approvalStatus").value("UNVERIFIED"));

        create(adminA, tenantA, "MADE_UP_INTENT", TEMPLATE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        create(adminA, tenantA, "repeat_purchase", TEMPLATE).andExpect(status().isNotFound());
        refresh(adminA, tenantA, "MADE_UP_INTENT", "\"0\"").andExpect(status().isNotFound());
        // A raw provider template id is not a member of the contract.
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE.replace("\"enabled\":true",
                "\"enabled\":true,\"providerTemplateId\":\"1259544702043867\"")).andExpect(status().isBadRequest());
        assertThat(mappingCount(tenantA)).isZero();
    }

    @Test
    void anOperatorCannotSetApprovalStateOrSendInvalidShapes() throws Exception {
        for (String json : List.of(
                TEMPLATE.replace("\"enabled\":true", "\"enabled\":true,\"approvalStatus\":\"APPROVED\""),
                TEMPLATE.replace("repeat_purchase_v1", "Repeat-Purchase"),
                TEMPLATE.replace("es_MX", "es-419"),
                TEMPLATE.replace("\"customer_name\",\"product\"", "\"customer_name\",\"customer_name\""),
                TEMPLATE.replace("\"customer_name\",\"product\"", "\"1bad\""),
                TEMPLATE.replace("\"customer_name\",\"product\"",
                        "\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\""),
                TEMPLATE.replace("\"enabled\":true", "\"enabled\":\"true\""),
                TEMPLATE.replace("\"parameterNames\":[\"customer_name\",\"product\"],", ""),
                "null", "[]", "{}")) {
            create(adminA, tenantA, "REPEAT_PURCHASE", json).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        assertThat(mappingCount(tenantA)).isZero();
        assertThat(auditCount(tenantA)).isZero();
    }

    @Test
    void createsAndReplacesWithEtagPreconditionsAndNeedsAnIntegration() throws Exception {
        // A tenant without an integration has no sender to bind a mapping to.
        Tenant bare = seedTenant(tenants, contexts, "Bare " + UUID.randomUUID(), Instant.now());
        IdentityId bareAdmin = member(bare, TenantRole.ADMIN);
        create(bareAdmin, bare, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTEGRATION_DISABLED"));

        replace(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"", TEMPLATE).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.approvalStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.sendable").value(false));
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isConflict());
        replace(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"", TEMPLATE.replace("_v1", "_v2")).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\"")).andExpect(jsonPath("$.providerTemplateName")
                        .value("repeat_purchase_v2"));
        replace(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"", TEMPLATE).andExpect(status().isConflict());
        for (String malformed : List.of("garbage", "1", "W/\"1\"", "\"01\"", "")) {
            replace(adminA, tenantA, "REPEAT_PURCHASE", malformed, TEMPLATE).andExpect(status().isBadRequest());
        }
        assertThat(auditCount(tenantA)).isEqualTo(2);
    }

    @Test
    void onlyProviderEvidenceMakesAMappingSendableAndEditsDropIt() throws Exception {
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk());
        assertThat(resolve(tenantA, SemanticTemplateIntent.REPEAT_PURCHASE))
                .isInstanceOf(TemplateMappingResolver.Disabled.class);
        assertThat(resolve(tenantA, SemanticTemplateIntent.RELATED_PRODUCT))
                .isInstanceOf(TemplateMappingResolver.NotMapped.class);

        gateway.next = new TemplateProviderGateway.Reported(TemplateApprovalStatus.APPROVED, WABA_ID);
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.sendable").value(true))
                .andExpect(jsonPath("$.version").value(0));
        var resolved = (TemplateMappingResolver.Resolved) resolve(tenantA, SemanticTemplateIntent.REPEAT_PURCHASE);
        assertThat(resolved.mapping().providerTemplateName()).isEqualTo("repeat_purchase_v1");
        assertThat(resolved.mapping().parameterNames()).containsExactly("customer_name", "product");
        assertThat(resolved.mapping().version()).isZero();

        // Changing the mapping bumps the revision and drops evidence: the approved send is no longer eligible.
        replace(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"", TEMPLATE.replace("_v1", "_v2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("UNVERIFIED"));
        assertThat(resolve(tenantA, SemanticTemplateIntent.REPEAT_PURCHASE))
                .isInstanceOf(TemplateMappingResolver.Disabled.class);
        // Disabling an approved mapping also makes it ineligible.
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"1\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.sendable").value(true));
        replace(adminA, tenantA, "REPEAT_PURCHASE", "\"1\"", TEMPLATE.replace("true", "false"))
                .andExpect(status().isOk());
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"2\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED")).andExpect(jsonPath("$.sendable").value(false));
        assertThat(resolve(tenantA, SemanticTemplateIntent.REPEAT_PURCHASE))
                .isInstanceOf(TemplateMappingResolver.Disabled.class);
    }

    @Test
    void everyNonApprovedProviderStateIsIneligible() throws Exception {
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk());
        for (TemplateApprovalStatus state : List.of(TemplateApprovalStatus.PENDING, TemplateApprovalStatus.REJECTED,
                TemplateApprovalStatus.PAUSED, TemplateApprovalStatus.DISABLED, TemplateApprovalStatus.DELETED)) {
            gateway.next = new TemplateProviderGateway.Reported(state, WABA_ID);
            refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isOk())
                    .andExpect(jsonPath("$.approvalStatus").value(state.name()))
                    .andExpect(jsonPath("$.sendable").value(false));
            assertThat(resolve(tenantA, SemanticTemplateIntent.REPEAT_PURCHASE))
                    .isInstanceOf(TemplateMappingResolver.Disabled.class);
        }
        gateway.next = new TemplateProviderGateway.Absent(WABA_ID);
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("NOT_FOUND"));
        assertThat(resolve(tenantA, SemanticTemplateIntent.REPEAT_PURCHASE))
                .isInstanceOf(TemplateMappingResolver.Disabled.class);
    }

    @Test
    void aFailedRefreshDowngradesEarlierEvidenceAndExposesOnlyAClosedCode() throws Exception {
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk());
        gateway.next = new TemplateProviderGateway.Reported(TemplateApprovalStatus.APPROVED, WABA_ID);
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isOk());

        gateway.next = new TemplateProviderGateway.Unavailable();
        String body = refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("repeat_purchase").doesNotContain(WABA_ID);
        assertThat(resolve(tenantA, SemanticTemplateIntent.REPEAT_PURCHASE))
                .isInstanceOf(TemplateMappingResolver.Disabled.class);
        list(adminA, tenantA).andExpect(jsonPath("$.items[?(@.templateIntent=='REPEAT_PURCHASE')].approvalStatus")
                .value("UNVERIFIED"));
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"7\"").andExpect(status().isConflict());
        refresh(adminA, tenantA, "RELATED_PRODUCT", "\"0\"").andExpect(status().isNotFound());
    }

    @Test
    void aSlowerOlderRefreshCannotOverwriteNewerEvidence() throws Exception {
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk());
        gateway.next = new TemplateProviderGateway.Reported(TemplateApprovalStatus.APPROVED, WABA_ID);
        // While the first refresh waits on the provider, a second one lands and stores REJECTED.
        gateway.duringLookup = () -> {
            gateway.duringLookup = () -> { };
            gateway.next = new TemplateProviderGateway.Reported(TemplateApprovalStatus.REJECTED, WABA_ID);
            try {
                refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isOk());
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        list(adminA, tenantA).andExpect(jsonPath("$.items[?(@.templateIntent=='REPEAT_PURCHASE')].approvalStatus")
                .value("REJECTED"));
        assertThat(resolve(tenantA, SemanticTemplateIntent.REPEAT_PURCHASE))
                .isInstanceOf(TemplateMappingResolver.Disabled.class);
    }

    @Test
    void evidenceForAnotherBusinessAccountIsNeverStored() throws Exception {
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk());
        gateway.next = new TemplateProviderGateway.Reported(TemplateApprovalStatus.APPROVED, WABA_ID);
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isOk());

        gateway.next = new TemplateProviderGateway.Reported(TemplateApprovalStatus.APPROVED, "999999999999999");
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
        gateway.next = new TemplateProviderGateway.Absent("999999999999999");
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isServiceUnavailable());
        list(adminA, tenantA).andExpect(jsonPath("$.items[?(@.templateIntent=='REPEAT_PURCHASE')].approvalStatus")
                .value("UNVERIFIED"));
    }

    @Test
    void anUnchangedUnavailableRefreshWritesNothing() throws Exception {
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk());
        String before = updatedAt(tenantA);
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isServiceUnavailable());
        assertThat(updatedAt(tenantA)).isEqualTo(before);
        assertThat(auditCount(tenantA)).isEqualTo(1);
    }

    @Test
    void repeatedUnavailableRefreshesStayIdempotent() throws Exception {
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk());
        for (int i = 0; i < 2; i++) {
            refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isServiceUnavailable());
        }
    }

    @Test
    void staleEvidenceAndAChangedBusinessAccountFailClosed() throws Exception {
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk());
        gateway.next = new TemplateProviderGateway.Reported(TemplateApprovalStatus.APPROVED, WABA_ID);
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isOk());
        assertThat(resolve(tenantA, SemanticTemplateIntent.REPEAT_PURCHASE))
                .isInstanceOf(TemplateMappingResolver.Resolved.class);

        execute("UPDATE dokene.tenant_provider_templates SET approval_checked_at = now() - interval '2 days' "
                + "WHERE tenant_id = ?", tenantA.id().value());
        assertThat(resolve(tenantA, SemanticTemplateIntent.REPEAT_PURCHASE))
                .isInstanceOf(TemplateMappingResolver.Disabled.class);

        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isOk());
        assertThat(resolve(tenantA, SemanticTemplateIntent.REPEAT_PURCHASE))
                .isInstanceOf(TemplateMappingResolver.Resolved.class);
        execute("UPDATE dokene.tenant_messaging_integrations SET business_account_id = '999999999999999' "
                + "WHERE tenant_id = ?", tenantA.id().value());
        assertThat(resolve(tenantA, SemanticTemplateIntent.REPEAT_PURCHASE))
                .isInstanceOf(TemplateMappingResolver.Disabled.class);
    }

    @Test
    void tenantsCannotSeeOrUseEachOthersMappingsAndRowLevelSecurityHolds() throws Exception {
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk());
        gateway.next = new TemplateProviderGateway.Reported(TemplateApprovalStatus.APPROVED, WABA_ID);
        refresh(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isOk());

        list(adminB, tenantB).andExpect(jsonPath("$.items[?(@.mapped==true)]").isEmpty());
        refresh(adminB, tenantB, "REPEAT_PURCHASE", "\"0\"").andExpect(status().isNotFound());
        assertThat(resolve(tenantB, SemanticTemplateIntent.REPEAT_PURCHASE))
                .isInstanceOf(TemplateMappingResolver.NotMapped.class);
        list(adminB, tenantA).andExpect(status().is4xxClientError());

        try (Connection runtime = runtimeConnectionWithoutContext()) {
            assertThat(queryInt(runtime, "SELECT count(*) FROM dokene.tenant_provider_templates")).isZero();
        }
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_policies WHERE tablename = ?",
                    "tenant_provider_templates")).isEqualTo(5);
            assertThat(queryInt(migration, "SELECT count(*) FROM pg_class WHERE relname = ? AND relrowsecurity "
                    + "AND relforcerowsecurity", "tenant_provider_templates")).isEqualTo(1);
            // The schema itself refuses a mapping that points at another tenant's integration.
            UUID foreignIntegration = integrationId(tenantB);
            assertThatThrownBy(() -> execute("""
                    INSERT INTO dokene.tenant_provider_templates (id, tenant_id, integration_id, semantic_intent,
                        provider_template_name, language, parameter_names, enabled, created_at, updated_at)
                    VALUES (gen_random_uuid(), ?, ?, 'SEASONAL_EVENT', 'x', 'es', '{}', true, now(), now())
                    """, tenantA.id().value(), foreignIntegration)).isInstanceOf(SQLException.class);
            // An approved status can never be kept without complete provider evidence.
            assertThatThrownBy(() -> execute("UPDATE dokene.tenant_provider_templates SET approval_checked_at = NULL "
                    + "WHERE tenant_id = ?", tenantA.id().value())).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void auditCarriesMetadataOnlyAndAFailureRollsTheChangeBack() throws Exception {
        create(adminA, tenantA, "REPEAT_PURCHASE", TEMPLATE).andExpect(status().isOk());
        try (Connection migration = migrationConnection();
                var statement = migration.prepareStatement("SELECT target_type, enabled FROM dokene.audit_events "
                        + "WHERE tenant_id = ? AND event_type = 'TEMPLATE_MAPPING_UPDATED'")) {
            statement.setObject(1, tenantA.id().value());
            try (var rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("TEMPLATE_MAPPING");
                // Typed columns only: the target id and the enabled flag, no names, languages or parameters.
                assertThat(rs.getBoolean(2)).isTrue();
                assertThat(rs.next()).isFalse();
            }
        }

        doThrow(new AuditPersistenceException()).when(audit).updated(any(), anyBoolean());
        replace(adminA, tenantA, "REPEAT_PURCHASE", "\"0\"", TEMPLATE.replace("_v1", "_v9"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
        create(adminA, tenantA, "RELATED_PRODUCT", TEMPLATE).andExpect(status().isServiceUnavailable());
        assertThat(mappingCount(tenantA)).isEqualTo(1);
        assertThat(mappingName(tenantA, "REPEAT_PURCHASE")).isEqualTo("repeat_purchase_v1");
        assertThat(auditCount(tenantA)).isEqualTo(1);
    }

    private TemplateMappingResolver.Resolution resolve(Tenant tenant, SemanticTemplateIntent intent) {
        var context = new TenantContext(tenant.id(), new IdentityId(UUID.randomUUID()),
                new TenantMembershipId(UUID.randomUUID()), TenantRole.ADMIN, TenantMembershipStatus.ACTIVE);
        return contexts.callWithContext(context, () -> resolver.resolve(intent));
    }

    private ResultActions list(IdentityId who, Tenant tenant) throws Exception {
        return mvc.perform(get("/api/templates").with(user(who)).header("X-Tenant-Id", tenant.id().value()));
    }

    private ResultActions create(IdentityId who, Tenant tenant, String intent, String json) throws Exception {
        return mvc.perform(put("/api/templates/" + intent).with(user(who)).with(csrf())
                .header("X-Tenant-Id", tenant.id().value()).header("If-None-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions replace(IdentityId who, Tenant tenant, String intent, String ifMatch, String json)
            throws Exception {
        return mvc.perform(put("/api/templates/" + intent).with(user(who)).with(csrf())
                .header("X-Tenant-Id", tenant.id().value()).header("If-Match", ifMatch)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions refresh(IdentityId who, Tenant tenant, String intent, String ifMatch) throws Exception {
        return mvc.perform(post("/api/templates/" + intent + "/approval-refresh").with(user(who)).with(csrf())
                .header("X-Tenant-Id", tenant.id().value()).header("If-Match", ifMatch));
    }

    private void seedIntegration(Tenant tenant) {
        String phone = Long.toString(NEXT_ID.incrementAndGet());
        contexts.runWithTenantId(tenant.id(), () -> integrations.insert(WhatsAppIntegration.create(tenant.id(), phone,
                WABA_ID, null, true, "wa-token-ok", Instant.now())));
    }

    private IdentityId member(Tenant tenant, TenantRole role) {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), identity, role, Instant.now());
        return identity;
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (Connection migration = migrationConnection(); var statement = migration.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private UUID integrationId(Tenant tenant) throws SQLException {
        try (Connection migration = migrationConnection();
                var statement = migration.prepareStatement(
                        "SELECT id FROM dokene.tenant_messaging_integrations WHERE tenant_id = ?")) {
            statement.setObject(1, tenant.id().value());
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private int integrationVersion(Tenant tenant) throws SQLException {
        try (Connection migration = migrationConnection()) {
            return queryInt(migration, "SELECT version FROM dokene.tenant_messaging_integrations "
                    + "WHERE tenant_id = ?", tenant.id().value());
        }
    }

    private String updatedAt(Tenant tenant) throws SQLException {
        try (Connection migration = migrationConnection();
                var statement = migration.prepareStatement(
                        "SELECT updated_at::text FROM dokene.tenant_provider_templates WHERE tenant_id = ?")) {
            statement.setObject(1, tenant.id().value());
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int mappingCount(Tenant tenant) throws SQLException {
        try (Connection migration = migrationConnection()) {
            return queryInt(migration, "SELECT count(*) FROM dokene.tenant_provider_templates WHERE tenant_id = ?",
                    tenant.id().value());
        }
    }

    private String mappingName(Tenant tenant, String intent) throws SQLException {
        try (Connection migration = migrationConnection();
                var statement = migration.prepareStatement("SELECT provider_template_name FROM "
                        + "dokene.tenant_provider_templates WHERE tenant_id = ? AND semantic_intent = ?")) {
            statement.setObject(1, tenant.id().value());
            statement.setString(2, intent);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int auditCount(Tenant tenant) throws SQLException {
        try (Connection migration = migrationConnection()) {
            return queryInt(migration, "SELECT count(*) FROM dokene.audit_events WHERE tenant_id = ? "
                    + "AND event_type = 'TEMPLATE_MAPPING_UPDATED'", tenant.id().value());
        }
    }

    private RequestPostProcessor user(IdentityId identityId) {
        var principal = new TestPrincipal(identityId);
        return authentication(new UsernamePasswordAuthenticationToken(principal, "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
}
