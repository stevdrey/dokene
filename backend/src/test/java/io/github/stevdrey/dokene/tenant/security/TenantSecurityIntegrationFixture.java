package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Shared PostgreSQL fixture for security tests that need real runtime and migration roles. */
final class TenantSecurityIntegrationFixture {

    static final String MIGRATION_ROLE = "dokene_migration";
    static final String RUNTIME_ROLE = "dokene_runtime";
    static final String MIGRATION_PASSWORD = "migration-" + UUID.randomUUID();
    static final String RUNTIME_PASSWORD = "runtime-" + UUID.randomUUID();
    static final String TENANT_CONTEXT_SIGNING_KEY = randomSigningKey();
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final AtomicBoolean DATABASE_ROLES_CREATED = new AtomicBoolean();

    private TenantSecurityIntegrationFixture() {
    }

    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        POSTGRES.start();
        if (DATABASE_ROLES_CREATED.compareAndSet(false, true)) {
            try {
                createDatabaseRoles();
            } catch (SQLException exception) {
                DATABASE_ROLES_CREATED.set(false);
                throw exception;
            }
        }

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_ROLE);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> MIGRATION_ROLE);
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("dokene.tenant-context.signing-key", () -> TENANT_CONTEXT_SIGNING_KEY);
    }

    static Tenant seedTenant(TenantRepository tenants, String displayName, Instant createdAt) {
        Tenant tenant = Tenant.create(TenantId.random(), displayName, createdAt);
        return tenants.save(tenant);
    }

    static TenantMembership seedMembership(
            TenantMembershipRepository memberships,
            TenantContextProvider contexts,
            TenantId tenantId,
            IdentityId identityId,
            TenantRole role,
            Instant createdAt
    ) {
        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(), tenantId, identityId, role, createdAt
        );
        contexts.runWithTenantId(tenantId, () -> memberships.save(membership));
        return membership;
    }

    static TenantContext context(TenantMembership membership) {
        return new TenantContext(
                membership.tenantId(), membership.identityId(), membership.id(), membership.role(), membership.status()
        );
    }

    static Connection runtimeConnection(SignedDatabaseContext context) throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
        try (PreparedStatement settings = connection.prepareStatement(
                "SELECT set_config(?, ?, false), set_config(?, ?, false)")) {
            settings.setString(1, "dokene.tenant_context");
            settings.setString(2, context.payload());
            settings.setString(3, "dokene.tenant_context_signature");
            settings.setString(4, context.signature());
            settings.execute();
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    static Connection runtimeConnectionWithoutContext() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
    }

    static Connection migrationConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATION_ROLE, MIGRATION_PASSWORD);
    }

    private static void createDatabaseRoles() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE ROLE dokene_migration LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS
                    PASSWORD '%s'
                    """.formatted(MIGRATION_PASSWORD));
            statement.execute("""
                    CREATE ROLE dokene_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS
                    PASSWORD '%s'
                    """.formatted(RUNTIME_PASSWORD));
            statement.execute("REVOKE ALL ON DATABASE %s FROM PUBLIC".formatted(POSTGRES.getDatabaseName()));
            statement.execute("GRANT CONNECT, CREATE ON DATABASE %s TO dokene_migration".formatted(POSTGRES.getDatabaseName()));
            statement.execute("GRANT CONNECT ON DATABASE %s TO dokene_runtime".formatted(POSTGRES.getDatabaseName()));
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        }
    }

    private static String randomSigningKey() {
        byte[] signingKey = new byte[32];
        new SecureRandom().nextBytes(signingKey);
        return HexFormat.of().formatHex(signingKey);
    }
}
