package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Canonical end-to-end regression suite for tenant authorization and PostgreSQL RLS.
 *
 * <p>Application authorization and direct runtime-role SQL are asserted separately so a
 * regression in either control is independently observable.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TenantIsolationSecurityIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-05T00:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = TenantSecurityIntegrationFixture.POSTGRES;

    @Autowired private TenantRepository tenants;
    @Autowired private TenantMembershipRepository memberships;
    @Autowired private TenantContextProvider contexts;
    @Autowired private TenantContextResolver contextResolver;
    @Autowired private AuthenticatedTenantIdentityResolver identityResolver;
    @Autowired private TenantAuthorizationService authorization;
    @Autowired private DatabaseContextSigner signer;
    @Autowired private AuditRecorder auditRecorder;
    @Autowired private AuditReader auditReader;
    @Autowired private AuditExecutionContext auditExecution;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsOnlyTheServerVerifiedTenantSelectorAndClearsTheRequestContext() throws Exception {
        Tenant tenantA = tenant("Tenant A");
        Tenant tenantB = tenant("Tenant B");
        IdentityId identity = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = membership(tenantA, identity, TenantRole.ADMIN);
        TenantMembership inactiveMembershipB = membership(tenantB, identity, TenantRole.ADMIN);
        inactiveMembershipB.suspend(CREATED_AT.plusSeconds(1));
        contexts.runWithTenantId(tenantB.id(), () -> memberships.save(inactiveMembershipB));
        authenticate(identity);

        TenantContextRequestFilter filter = new TenantContextRequestFilter(
                contexts, contextResolver, identityResolver, new TenantScopedRequestMatcher()
        );
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        filter.doFilter(requestFor(tenantA.id()), allowedResponse, (request, response) -> {
            assertThat(contexts.requireCurrent()).isEqualTo(TenantSecurityIntegrationFixture.context(membershipA));
        });

        assertThat(allowedResponse.getStatus()).isEqualTo(200);
        assertThat(contexts.current()).isEmpty();

        MockHttpServletResponse forgedResponse = new MockHttpServletResponse();
        filter.doFilter(requestFor(tenantB.id()), forgedResponse, (request, response) -> {
            throw new AssertionError("A selector without an active membership must not reach the resource");
        });

        assertThat(forgedResponse.getStatus()).isEqualTo(403);
        assertThat(contexts.current()).isEmpty();
    }

    @Test
    void authorizesRolesAndFailsClosedForMissingOrForeignResources() {
        Tenant tenantA = tenant("Tenant A");
        Tenant tenantB = tenant("Tenant B");
        TenantContext owner = context(membership(tenantA, new IdentityId(UUID.randomUUID()), TenantRole.OWNER));
        TenantContext viewer = context(membership(tenantA, new IdentityId(UUID.randomUUID()), TenantRole.VIEWER));

        contexts.runWithContext(owner, () -> authorization.requirePermission(TenantPermission.CUSTOMER_WRITE));

        assertDenied(() -> contexts.runWithContext(viewer,
                () -> authorization.requirePermission(TenantPermission.CUSTOMER_WRITE)));
        assertDenied(() -> authorization.requirePermission(TenantPermission.CUSTOMER_READ));
        assertDenied(() -> contexts.runWithContext(owner,
                () -> authorization.requireResourceAccess(TenantPermission.CUSTOMER_READ, tenantB.id())));
    }

    @Test
    void enforcesRlsAgainstDirectRuntimeSqlWithoutApplicationPredicates() throws Exception {
        Tenant tenantA = tenant("Tenant A");
        Tenant tenantB = tenant("Tenant B");
        TenantMembership membershipA = membership(tenantA, new IdentityId(UUID.randomUUID()), TenantRole.ADMIN);
        TenantMembership membershipB = membership(tenantB, new IdentityId(UUID.randomUUID()), TenantRole.ADMIN);

        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnection(signer.issueTenantContext(tenantA.id()));
                Statement statement = connection.createStatement()) {
            assertThat(ids(statement.executeQuery("SELECT id FROM dokene.tenant_memberships ORDER BY id")))
                    .containsExactly(membershipA.id().value());
            assertThat(ids(statement.executeQuery("SELECT id FROM dokene.tenant_memberships WHERE id = '"
                    + membershipB.id().value() + "'"))).isEmpty();
            assertThat(statement.executeUpdate("UPDATE dokene.tenant_memberships SET role = 'VIEWER' WHERE id = '"
                    + membershipB.id().value() + "'")).isZero();
            assertThat(statement.executeUpdate("DELETE FROM dokene.tenant_memberships WHERE id = '"
                    + membershipB.id().value() + "'")).isZero();
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO dokene.tenant_memberships
                        (id, tenant_id, identity_id, role, status, created_at, updated_at, version)
                    VALUES (gen_random_uuid(), '%s', gen_random_uuid(), 'OPERATOR', 'ACTIVE', now(), now(), 0)
                    """.formatted(tenantB.id().value()))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE dokene.tenant_memberships SET tenant_id = '"
                    + tenantB.id().value() + "' WHERE id = '" + membershipA.id().value() + "'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void isolatesAuditReadsAtBothTheApplicationAndDatabaseLayers() throws Exception {
        TenantContext contextA = context(membership(tenant("Tenant A"), new IdentityId(UUID.randomUUID()), TenantRole.ADMIN));
        TenantContext contextB = context(membership(tenant("Tenant B"), new IdentityId(UUID.randomUUID()), TenantRole.ADMIN));
        recordDenial(contextA);
        recordDenial(contextB);
        UUID globalCorrelation = recordGlobalDenial();

        contexts.runWithContext(contextA, () -> assertThat(auditReader.read().events())
                .allSatisfy(event -> assertThat(event.tenantId()).isEqualTo(contextA.tenantId())));
        contexts.runWithContext(contextB, () -> assertThat(auditReader.read().events())
                .allSatisfy(event -> assertThat(event.tenantId()).isEqualTo(contextB.tenantId())));

        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnection(signer.issueTenantContext(contextA.tenantId()));
                Statement statement = connection.createStatement()) {
            assertThat(ids(statement.executeQuery("SELECT id FROM dokene.audit_events ORDER BY id"))).hasSize(1);
        }
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                var statement = connection.prepareStatement(
                        "SELECT count(*) FROM dokene.audit_events WHERE tenant_id IS NULL AND correlation_id = ?")) {
            statement.setObject(1, globalCorrelation);
            try (ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext();
                Statement statement = connection.createStatement()) {
            assertThat(ids(statement.executeQuery("SELECT id FROM dokene.audit_events"))).isEmpty();
        }
    }

    @Test
    void clearsTenantStateAcrossAReusedWorkerAndPooledConnection() throws Exception {
        Tenant tenantA = tenant("Tenant A");
        Tenant tenantB = tenant("Tenant B");
        TenantMembership membershipA = membership(tenantA, new IdentityId(UUID.randomUUID()), TenantRole.ADMIN);
        membership(tenantB, new IdentityId(UUID.randomUUID()), TenantRole.ADMIN);

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            assertThat(worker.submit(() -> contexts.callWithTenantId(tenantA.id(), () -> jdbc.queryForList(
                    "SELECT id FROM dokene.tenant_memberships", UUID.class))).get(10, TimeUnit.SECONDS))
                    .containsExactly(membershipA.id().value());
            assertThat(worker.submit(() -> jdbc.queryForList(
                    "SELECT id FROM dokene.tenant_memberships", UUID.class)).get(10, TimeUnit.SECONDS)).isEmpty();
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void runtimeRoleCannotMutateSchemaOrBypassRls() throws Exception {
        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("CREATE TABLE dokene.runtime_ddl_denied (id INTEGER)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute("ALTER TABLE dokene.tenant_memberships DISABLE ROW LEVEL SECURITY"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeQuery("SELECT signing_key FROM dokene.tenant_context_signing_keys"))
                    .isInstanceOf(SQLException.class);
            statement.execute("SET row_security = off");
            assertThatThrownBy(() -> statement.executeQuery("SELECT id FROM dokene.tenant_memberships"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private Tenant tenant(String displayName) {
        return TenantSecurityIntegrationFixture.seedTenant(tenants, displayName, CREATED_AT);
    }

    private TenantMembership membership(Tenant tenant, IdentityId identity, TenantRole role) {
        return TenantSecurityIntegrationFixture.seedMembership(
                memberships, contexts, tenant.id(), identity, role, CREATED_AT
        );
    }

    private TenantContext context(TenantMembership membership) {
        return TenantSecurityIntegrationFixture.context(membership);
    }

    private void authenticate(IdentityId identity) {
        AuthenticatedTenantIdentity principal = () -> identity;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "not-used", List.of())
        );
    }

    private MockHttpServletRequest requestFor(TenantId tenantId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenant-resource");
        request.addHeader(TenantContextRequestFilter.TENANT_ID_HEADER, tenantId.value().toString());
        return request;
    }

    private void assertDenied(Runnable operation) {
        auditExecution.runWithCorrelation(UUID.randomUUID(), () -> assertThatThrownBy(operation::run)
                .isInstanceOf(TenantAccessDeniedException.class));
    }

    private void recordDenial(TenantContext context) {
        auditExecution.runWithCorrelation(UUID.randomUUID(), () -> contexts.runWithContext(context,
                () -> auditRecorder.authorizationDenied(TenantPermission.AUDIT_READ, AuditDenialReason.UNSPECIFIED)));
    }

    private UUID recordGlobalDenial() {
        UUID correlation = UUID.randomUUID();
        auditExecution.runWithCorrelation(correlation,
                () -> auditRecorder.authorizationDenied(TenantPermission.AUDIT_READ, AuditDenialReason.NO_TENANT_CONTEXT));
        return correlation;
    }

    private List<UUID> ids(ResultSet rows) throws SQLException {
        try (rows) {
            List<UUID> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(rows.getObject("id", UUID.class));
            }
            return ids;
        }
    }
}
