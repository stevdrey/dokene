// ABOUTME: Drives the real registry-backed template resolver end to end: mapping, scripted provider evidence,
// ABOUTME: expiry, edits and tenant isolation decide whether it resolves. Only the provider is scripted (issue 135).
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.migrationConnection;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver;
import io.github.stevdrey.dokene.template.application.TemplateProviderGateway;
import io.github.stevdrey.dokene.template.application.TemplateRegistryService;
import io.github.stevdrey.dokene.template.application.TemplateRegistryService.Create;
import io.github.stevdrey.dokene.template.application.TemplateRegistryService.Version;
import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema;
import io.github.stevdrey.dokene.template.domain.TemplateApprovalStatus;
import io.github.stevdrey.dokene.template.domain.TemplateParameterFormat;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "dokene.ai.provider=fake")
@Import(ScriptedTemplateProviderGateway.Configuration.class)
class RegistryBackedTemplateGateIntegrationTest {
    private static final AtomicLong NEXT_ID = new AtomicLong(
            500_000_000_000_000L + (System.nanoTime() % 1_000_000_000L) * 100);
    private static final String WABA_ID = "600000000000001";
    private static final SemanticTemplateIntent INTENT = SemanticTemplateIntent.REPEAT_PURCHASE;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    @Autowired TemplateMappingResolver resolver;
    @Autowired TemplateRegistryService registry;
    @Autowired ScriptedTemplateProviderGateway provider;
    @Autowired WhatsAppIntegrationRepository integrations;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;

    private Tenant tenantA;
    private Tenant tenantB;
    private TenantContext adminA;
    private TenantContext adminB;

    @BeforeEach
    void setUp() {
        provider.next = new TemplateProviderGateway.Unavailable();
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, contexts, "Gate A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, contexts, "Gate B " + UUID.randomUUID(), now);
        adminA = admin(tenantA);
        adminB = admin(tenantB);
        seedIntegration(tenantA);
        seedIntegration(tenantB);
    }

    @Test
    void anUnmappedOrUnverifiedIntentIsNotMapped() {
        assertThat(check(adminA)).isFalse();
        put(adminA, new Create(), "repeat_purchase_v1");
        assertThat(check(adminA)).isFalse();
    }

    @Test
    void onlyFreshProviderApprovalForTheCurrentRevisionIsMapped() throws Exception {
        put(adminA, new Create(), "repeat_purchase_v1");
        approve(adminA, 0);
        assertThat(check(adminA)).isTrue();

        for (TemplateApprovalStatus state : List.of(TemplateApprovalStatus.REJECTED, TemplateApprovalStatus.PAUSED)) {
            provider.next = new TemplateProviderGateway.Reported(state, WABA_ID);
            call(adminA, () -> registry.refreshApproval(INTENT, 0));
            assertThat(check(adminA)).isFalse();
        }

        approve(adminA, 0);
        assertThat(check(adminA)).isTrue();
        execute("UPDATE dokene.tenant_provider_templates SET approval_checked_at = now() - interval '2 days' "
                + "WHERE tenant_id = ?", tenantA.id().value());
        assertThat(check(adminA)).isFalse();
    }

    @Test
    void aProviderApprovalWhoseParametersDoNotMatchIsNeverMapped() {
        put(adminA, new Create(), "repeat_purchase_v1");
        for (ProviderTemplateSchema schema : List.of(
                new ProviderTemplateSchema.BodyParameters(TemplateParameterFormat.POSITIONAL, List.of("1", "2")),
                new ProviderTemplateSchema.BodyParameters(TemplateParameterFormat.POSITIONAL, List.of()),
                new ProviderTemplateSchema.Unsupported(), new ProviderTemplateSchema.Unreadable())) {
            provider.next = new TemplateProviderGateway.Reported(TemplateApprovalStatus.APPROVED, WABA_ID, schema);
            call(adminA, () -> registry.refreshApproval(INTENT, 0));
            assertThat(check(adminA)).isFalse();
        }
        approve(adminA, 0);
        assertThat(check(adminA)).isTrue();
    }

    @Test
    void editingAnApprovedMappingMakesItNotMappedUntilVerifiedAgain() {
        put(adminA, new Create(), "repeat_purchase_v1");
        approve(adminA, 0);
        assertThat(check(adminA)).isTrue();

        put(adminA, new Version(0), "repeat_purchase_v2");
        assertThat(check(adminA)).isFalse();
        approve(adminA, 1);
        assertThat(check(adminA)).isTrue();
    }

    @Test
    void anotherTenantIsNeverMappedByThisTenantsApproval() {
        put(adminA, new Create(), "repeat_purchase_v1");
        approve(adminA, 0);
        assertThat(check(adminA)).isTrue();
        assertThat(check(adminB)).isFalse();
    }

    /** Sendable means the resolver answers Resolved; submit, approval and send all ask it the same way. */
    private boolean check(TenantContext context) {
        return call(context, () -> resolver.resolve(INTENT) instanceof TemplateMappingResolver.Resolved);
    }

    private void put(TenantContext context, TemplateRegistryService.Precondition precondition, String name) {
        call(context, () -> registry.put(INTENT, precondition, name, "es_MX", List.of("customer_name"), true));
    }

    private void approve(TenantContext context, long version) {
        provider.next = new TemplateProviderGateway.Reported(TemplateApprovalStatus.APPROVED, WABA_ID,
                new ProviderTemplateSchema.BodyParameters(TemplateParameterFormat.POSITIONAL, List.of("1")));
        call(context, () -> registry.refreshApproval(INTENT, version));
    }

    private <T> T call(TenantContext context, TenantContextProvider.ScopedOperation<T, RuntimeException> operation) {
        return contexts.callWithContext(context,
                () -> auditExecution.callWithCorrelation(UUID.randomUUID(), operation::execute));
    }

    private TenantContext admin(Tenant tenant) {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), identity, TenantRole.ADMIN, Instant.now());
        return new TenantContext(tenant.id(), identity, new TenantMembershipId(UUID.randomUUID()), TenantRole.ADMIN,
                TenantMembershipStatus.ACTIVE);
    }

    private void seedIntegration(Tenant tenant) {
        String phone = Long.toString(NEXT_ID.incrementAndGet());
        contexts.runWithTenantId(tenant.id(), () -> integrations.insert(WhatsAppIntegration.create(tenant.id(), phone,
                WABA_ID, null, true, "wa-token-ok", Instant.now())));
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (Connection migration = migrationConnection(); var statement = migration.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }
}
