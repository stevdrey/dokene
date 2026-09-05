package io.github.stevdrey.dokene.tenant.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantMembershipDiscovery;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.domain.TenantStatus;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Wrapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TenantPersistenceIntegrationTest {

    private static final String MIGRATION_ROLE = "dokene_migration";
    private static final String RUNTIME_ROLE = "dokene_runtime";
    private static final String MIGRATION_PASSWORD = "migration-" + UUID.randomUUID();
    private static final String RUNTIME_PASSWORD = "runtime-" + UUID.randomUUID();
    private static final String TENANT_CONTEXT_SIGNING_KEY = randomSigningKey();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JpaTenantRepositoryAdapter tenantRepository;

    @Autowired
    private JpaTenantMembershipRepositoryAdapter membershipRepository;

    @Autowired
    private TenantContextProvider tenantContextProvider;

    @Autowired
    private DatabaseContextSigner databaseContextSigner;

    @Autowired
    private TenantMembershipDiscovery tenantMembershipDiscovery;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Flyway flyway;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) throws SQLException {
        POSTGRES.start();
        createDatabaseRoles();

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_ROLE);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> MIGRATION_ROLE);
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("dokene.tenant-context.signing-key", () -> TENANT_CONTEXT_SIGNING_KEY);
    }

    @Test
    void migratesTheTenantFoundationWithLeastPrivilegeRuntimeAccess() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'dokene' ORDER BY tablename",
                String.class
        )).containsExactly("audit_events", "flyway_schema_history", "tenant_context_signing_keys", "tenant_memberships", "tenants");
        assertThat(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'dokene' ORDER BY table_name",
                String.class
        )).containsExactly("audit_events", "tenant_memberships", "tenants");
        assertThat(tableOwner("tenants")).isEqualTo(MIGRATION_ROLE);
        assertThat(tableOwner("tenant_memberships")).isEqualTo(MIGRATION_ROLE);
        assertThat(tableOwner("tenant_context_signing_keys")).isEqualTo(MIGRATION_ROLE);
        assertThat(tableOwner("flyway_schema_history")).isEqualTo(MIGRATION_ROLE);

        Map<String, Object> runtimeRole = jdbcTemplate.queryForMap("""
                SELECT rolbypassrls, rolsuper, rolcreaterole, rolcreatedb
                FROM pg_roles
                WHERE rolname = 'dokene_runtime'
                """);
        assertThat(runtimeRole)
                .containsEntry("rolbypassrls", false)
                .containsEntry("rolsuper", false)
                .containsEntry("rolcreaterole", false)
                .containsEntry("rolcreatedb", false);

        Map<String, Object> rlsStatus = jdbcTemplate.queryForMap("""
                SELECT c.relrowsecurity, c.relforcerowsecurity
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'dokene' AND c.relname = 'tenant_memberships'
                """);
        assertThat(rlsStatus)
                .containsEntry("relrowsecurity", true)
                .containsEntry("relforcerowsecurity", true);

        List<Map<String, Object>> policies = jdbcTemplate.queryForList("""
                SELECT policyname, cmd, roles
                FROM pg_policies
                WHERE schemaname = 'dokene' AND tablename = 'tenant_memberships'
                ORDER BY policyname
                """);
        assertThat(policies).hasSize(5);
        assertThat(policies).extracting("policyname").containsExactly(
                "tenant_memberships_delete_policy",
                "tenant_memberships_insert_policy",
                "tenant_memberships_migration_policy",
                "tenant_memberships_select_policy",
                "tenant_memberships_update_policy"
        );

        assertThatThrownBy(() -> executeAsRuntime("CREATE TABLE dokene.runtime_ddl_denied (id INTEGER)"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> executeAsRuntime("ALTER TABLE dokene.tenants ADD COLUMN runtime_ddl_denied BOOLEAN"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> executeAsRuntime("ALTER TABLE dokene.tenant_memberships DISABLE ROW LEVEL SECURITY"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> executeAsRuntime("DROP TABLE dokene.tenants"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> executeAsRuntime("SELECT signing_key FROM dokene.tenant_context_signing_keys"))
                .isInstanceOf(SQLException.class);
        assertThat(tableOwner("tenants")).isEqualTo(MIGRATION_ROLE);
    }

    @Test
    void rejectsUnexpectedNonEmptySchemasWithoutBaseliningThem() throws SQLException {
        String unexpectedSchema = "unexpected_schema";
        try {
            executeAsMigration("CREATE SCHEMA " + unexpectedSchema);
            executeAsMigration("CREATE TABLE " + unexpectedSchema + ".legacy_table (id INTEGER PRIMARY KEY)");

            Flyway unexpectedFlyway = Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), MIGRATION_ROLE, MIGRATION_PASSWORD)
                    .schemas(unexpectedSchema)
                    .defaultSchema(unexpectedSchema)
                    .createSchemas(true)
                    .baselineOnMigrate(false)
                    .cleanDisabled(true)
                    .load();

            assertThatThrownBy(unexpectedFlyway::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("non-empty schema");
        } finally {
            executeAsMigration("DROP SCHEMA IF EXISTS " + unexpectedSchema + " CASCADE");
        }
    }

    @Test
    void persistsAndRestoresTenantAndMembershipState() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = Tenant.create(TenantId.random(), "  Main workspace  ", createdAt);
        tenant.suspend(createdAt.plusSeconds(60));
        Tenant persistedTenant = tenantRepository.save(tenant);

        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(),
                tenant.id(),
                new IdentityId(UUID.randomUUID()),
                TenantRole.ADMIN,
                createdAt
        );
        membership.suspend(createdAt.plusSeconds(120));
        TenantMembership persistedMembership = saveMembership(membership);

        Tenant restoredTenant = tenantRepository.findById(tenant.id()).orElseThrow();
        TenantMembership restoredMembership = findMembership(tenant.id(), membership.identityId()).orElseThrow();

        assertThat(restoredTenant.displayName()).isEqualTo("Main workspace");
        assertThat(restoredTenant.status()).isEqualTo(tenant.status());
        assertThat(restoredTenant.createdAt()).isEqualTo(createdAt);
        assertThat(restoredTenant.updatedAt()).isEqualTo(createdAt.plusSeconds(60));
        assertThat(persistedTenant.revision()).hasValue(0);
        assertThat(restoredTenant.revision()).hasValue(0);
        assertThat(restoredMembership.id()).isEqualTo(membership.id());
        assertThat(restoredMembership.status()).isEqualTo(membership.status());
        assertThat(restoredMembership.createdAt()).isEqualTo(createdAt);
        assertThat(restoredMembership.updatedAt()).isEqualTo(createdAt.plusSeconds(120));
        assertThat(persistedMembership.revision()).hasValue(0);
        assertThat(restoredMembership.revision()).hasValue(0);
    }

    @Test
    void permitsTheSameIdentityInDifferentTenants() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        Tenant firstTenant = persistTenant("First workspace", createdAt);
        Tenant secondTenant = persistTenant("Second workspace", createdAt);

        saveMembership(TenantMembership.createActive(
                TenantMembershipId.random(), firstTenant.id(), identityId, TenantRole.OPERATOR, createdAt
        ));
        saveMembership(TenantMembership.createActive(
                TenantMembershipId.random(), secondTenant.id(), identityId, TenantRole.OPERATOR, createdAt
        ));

        assertThat(findMembership(firstTenant.id(), identityId)).isPresent();
        assertThat(findMembership(secondTenant.id(), identityId)).isPresent();
    }

    @Test
    void rejectsDuplicateMembershipsForTheSameTenantAndIdentity() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = persistTenant("Workspace", createdAt);
        IdentityId identityId = new IdentityId(UUID.randomUUID());

        saveMembership(TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), identityId, TenantRole.OPERATOR, createdAt
        ));

        assertThatThrownBy(() -> saveMembership(TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), identityId, TenantRole.VIEWER, createdAt
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMembershipsForUnknownTenants() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");

        assertThatThrownBy(() -> saveMembership(TenantMembership.createActive(
                TenantMembershipId.random(), TenantId.random(), new IdentityId(UUID.randomUUID()),
                TenantRole.OPERATOR, createdAt
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsWhitespaceOnlyTenantNamesAtTheDatabaseBoundary() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");

        for (String displayName : new String[]{"\u0085", "\u00A0", "\u2007", "\u202F", "\u3000"}) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO tenants (id, display_name, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), displayName, "ACTIVE", Timestamp.from(createdAt), Timestamp.from(createdAt)
            )).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void persistsDisplayNamesAtTheUnicodeCodePointLimit() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        String displayName = "😀".repeat(Tenant.DISPLAY_NAME_MAX_LENGTH);

        Tenant persistedTenant = tenantRepository.save(Tenant.create(TenantId.random(), displayName, createdAt));

        assertThat(persistedTenant.displayName()).isEqualTo(displayName);
        assertThat(tenantRepository.findById(persistedTenant.id()).orElseThrow().displayName()).isEqualTo(displayName);
    }

    @Test
    void roundTripsTimestampsAtPostgresPrecision() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00.123456789Z");
        Instant tenantUpdatedAt = Instant.parse("2026-08-23T00:01:00.999999900Z");
        Instant membershipUpdatedAt = Instant.parse("2026-08-23T00:02:00.999999900Z");
        Tenant tenant = Tenant.create(TenantId.random(), "Workspace", createdAt);
        tenant.suspend(tenantUpdatedAt);
        Tenant persistedTenant = tenantRepository.save(tenant);
        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), new IdentityId(UUID.randomUUID()), TenantRole.OPERATOR, createdAt
        );
        membership.suspend(membershipUpdatedAt);
        TenantMembership persistedMembership = saveMembership(membership);

        Tenant restoredTenant = tenantRepository.findById(tenant.id()).orElseThrow();
        TenantMembership restoredMembership = findMembership(tenant.id(), membership.identityId()).orElseThrow();

        assertThat(persistedTenant).isSameAs(tenant);
        assertThat(tenant.createdAt()).isEqualTo(Instant.parse("2026-08-23T00:00:00.123457Z"));
        assertThat(tenant.updatedAt()).isEqualTo(Instant.parse("2026-08-23T00:01:01Z"));
        assertThat(restoredTenant.createdAt()).isEqualTo(tenant.createdAt());
        assertThat(restoredTenant.updatedAt()).isEqualTo(tenant.updatedAt());
        assertThat(persistedMembership).isSameAs(membership);
        assertThat(membership.createdAt()).isEqualTo(Instant.parse("2026-08-23T00:00:00.123457Z"));
        assertThat(membership.updatedAt()).isEqualTo(Instant.parse("2026-08-23T00:02:01Z"));
        assertThat(restoredMembership.createdAt()).isEqualTo(membership.createdAt());
        assertThat(restoredMembership.updatedAt()).isEqualTo(membership.updatedAt());
    }

    @Test
    void synchronizesTenantRevisionWhenSavingTheSameInstance() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = Tenant.create(TenantId.random(), "Workspace", createdAt);

        tenantRepository.save(tenant);
        assertThat(tenant.revision()).hasValue(0);

        tenant.suspend(createdAt.plusSeconds(60));
        tenantRepository.save(tenant);
        assertThat(tenant.revision()).hasValue(1);

        tenant.activate(createdAt.plusSeconds(120));
        tenantRepository.save(tenant);

        assertThat(tenant.revision()).hasValue(2);
        assertThat(tenantRepository.findById(tenant.id()).orElseThrow().status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void synchronizesMembershipRevisionWhenSavingTheSameInstance() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = persistTenant("Workspace", createdAt);
        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), new IdentityId(UUID.randomUUID()), TenantRole.OPERATOR, createdAt
        );

        saveMembership(membership);
        assertThat(membership.revision()).hasValue(0);

        membership.suspend(createdAt.plusSeconds(60));
        saveMembership(membership);
        assertThat(membership.revision()).hasValue(1);

        membership.revoke(createdAt.plusSeconds(120));
        saveMembership(membership);

        assertThat(membership.revision()).hasValue(2);
        assertThat(findMembership(tenant.id(), membership.identityId()).orElseThrow().status())
                .isEqualTo(TenantMembershipStatus.REVOKED);
    }

    @Test
    void persistsRoleChangesAndRejectsStaleRoleUpdates() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = persistTenant("Workspace", createdAt);
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), identityId, TenantRole.VIEWER, createdAt
        );
        saveMembership(membership);

        membership.changeRole(TenantRole.OPERATOR, createdAt.plusSeconds(60));
        saveMembership(membership);

        TenantMembership winningCopy = findMembership(tenant.id(), identityId).orElseThrow();
        TenantMembership staleCopy = findMembership(tenant.id(), identityId).orElseThrow();

        winningCopy.changeRole(TenantRole.ADMIN, createdAt.plusSeconds(120));
        saveMembership(winningCopy);

        staleCopy.changeRole(TenantRole.OWNER, createdAt.plusSeconds(180));

        assertThatThrownBy(() -> saveMembership(staleCopy))
                .isInstanceOf(OptimisticLockingFailureException.class);

        TenantMembership restoredMembership = findMembership(tenant.id(), identityId).orElseThrow();
        assertThat(restoredMembership.role()).isEqualTo(TenantRole.ADMIN);
        assertThat(restoredMembership.updatedAt()).isEqualTo(createdAt.plusSeconds(120));
        assertThat(restoredMembership.revision()).hasValue(2);
    }

    @Test
    void restoresTenantRevisionsAfterOuterTransactionRollback() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant existingTenant = persistTenant("Existing workspace", createdAt);
        Tenant newTenant = Tenant.create(TenantId.random(), "New workspace", createdAt);

        executeAndRollBack(() -> {
            existingTenant.suspend(createdAt.plusSeconds(60));
            tenantRepository.save(existingTenant);
            assertThat(existingTenant.revision()).hasValue(1);

            existingTenant.activate(createdAt.plusSeconds(120));
            tenantRepository.save(existingTenant);
            assertThat(existingTenant.revision()).hasValue(2);

            tenantRepository.save(newTenant);
            assertThat(newTenant.revision()).hasValue(0);
        });

        assertThat(existingTenant.revision()).hasValue(0);
        assertThat(newTenant.revision()).isEmpty();
        assertThat(tenantRepository.findById(existingTenant.id()).orElseThrow().status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenantRepository.findById(newTenant.id())).isEmpty();

        existingTenant.suspend(createdAt.plusSeconds(180));
        tenantRepository.save(existingTenant);
        tenantRepository.save(newTenant);

        assertThat(existingTenant.revision()).hasValue(1);
        assertThat(newTenant.revision()).hasValue(0);
        assertThat(tenantRepository.findById(existingTenant.id()).orElseThrow().status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(tenantRepository.findById(newTenant.id())).isPresent();
    }

    @Test
    void restoresMembershipRevisionsAfterOuterTransactionRollback() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = persistTenant("Workspace", createdAt);
        TenantMembership existingMembership = TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), new IdentityId(UUID.randomUUID()), TenantRole.OPERATOR, createdAt
        );
        saveMembership(existingMembership);
        TenantMembership newMembership = TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), new IdentityId(UUID.randomUUID()), TenantRole.VIEWER, createdAt
        );

        executeAndRollBack(() -> {
            existingMembership.suspend(createdAt.plusSeconds(60));
            saveMembership(existingMembership);
            assertThat(existingMembership.revision()).hasValue(1);

            existingMembership.activate(createdAt.plusSeconds(120));
            saveMembership(existingMembership);
            assertThat(existingMembership.revision()).hasValue(2);

            saveMembership(newMembership);
            assertThat(newMembership.revision()).hasValue(0);
        });

        assertThat(existingMembership.revision()).hasValue(0);
        assertThat(newMembership.revision()).isEmpty();
        assertThat(findMembership(tenant.id(), existingMembership.identityId()).orElseThrow().status())
                .isEqualTo(TenantMembershipStatus.ACTIVE);
        assertThat(findMembership(tenant.id(), newMembership.identityId())).isEmpty();

        existingMembership.suspend(createdAt.plusSeconds(180));
        saveMembership(existingMembership);
        saveMembership(newMembership);

        assertThat(existingMembership.revision()).hasValue(1);
        assertThat(newMembership.revision()).hasValue(0);
        assertThat(findMembership(tenant.id(), existingMembership.identityId()).orElseThrow().status())
                .isEqualTo(TenantMembershipStatus.SUSPENDED);
        assertThat(findMembership(tenant.id(), newMembership.identityId())).isPresent();
    }

    @Test
    void restoresInnerMembershipRevisionWhenRequiresNewTransactionRollsBack() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant outerTenant = persistTenant("Outer workspace", createdAt);
        TenantMembership rolledBackMembership = TenantMembership.createActive(
                TenantMembershipId.random(), outerTenant.id(), new IdentityId(UUID.randomUUID()), TenantRole.VIEWER, createdAt
        );

        executeInTransaction(() -> {
            outerTenant.suspend(createdAt.plusSeconds(60));
            tenantRepository.save(outerTenant);
            assertThat(outerTenant.revision()).hasValue(1);

            executeRequiresNewAndRollBack(() -> {
                saveMembership(rolledBackMembership);
                assertThat(rolledBackMembership.revision()).hasValue(0);
            });

            assertThat(rolledBackMembership.revision()).isEmpty();
        });

        assertThat(outerTenant.revision()).hasValue(1);
        assertThat(tenantRepository.findById(outerTenant.id()).orElseThrow().status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(findMembership(outerTenant.id(), rolledBackMembership.identityId())).isEmpty();

        saveMembership(rolledBackMembership);

        assertThat(rolledBackMembership.revision()).hasValue(0);
        assertThat(findMembership(outerTenant.id(), rolledBackMembership.identityId())).isPresent();
    }

    @Test
    void retainsInnerTenantRevisionWhenOuterTransactionRollsBack() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant membershipTenant = persistTenant("Membership workspace", createdAt);
        TenantMembership outerMembership = TenantMembership.createActive(
                TenantMembershipId.random(), membershipTenant.id(), new IdentityId(UUID.randomUUID()), TenantRole.OPERATOR, createdAt
        );
        saveMembership(outerMembership);
        Tenant innerTenant = persistTenant("Inner workspace", createdAt);

        executeAndRollBack(() -> {
            outerMembership.suspend(createdAt.plusSeconds(60));
            saveMembership(outerMembership);
            assertThat(outerMembership.revision()).hasValue(1);

            executeRequiresNew(() -> {
                innerTenant.suspend(createdAt.plusSeconds(60));
                tenantRepository.save(innerTenant);
                assertThat(innerTenant.revision()).hasValue(1);
            });

            assertThat(innerTenant.revision()).hasValue(1);
        });

        assertThat(outerMembership.revision()).hasValue(0);
        assertThat(findMembership(membershipTenant.id(), outerMembership.identityId()).orElseThrow().status())
                .isEqualTo(TenantMembershipStatus.ACTIVE);
        assertThat(innerTenant.revision()).hasValue(1);
        assertThat(tenantRepository.findById(innerTenant.id()).orElseThrow().status()).isEqualTo(TenantStatus.SUSPENDED);

        innerTenant.activate(createdAt.plusSeconds(120));
        tenantRepository.save(innerTenant);

        assertThat(innerTenant.revision()).hasValue(2);
        assertThat(tenantRepository.findById(innerTenant.id()).orElseThrow().status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void rejectsStaleMembershipUpdatesAfterRevocation() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = persistTenant("Workspace", createdAt);
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), identityId, TenantRole.OPERATOR, createdAt
        );
        membership.suspend(createdAt.plusSeconds(60));
        saveMembership(membership);

        TenantMembership revocationCopy = findMembership(tenant.id(), identityId).orElseThrow();
        TenantMembership staleActivationCopy = findMembership(tenant.id(), identityId).orElseThrow();

        revocationCopy.revoke(createdAt.plusSeconds(120));
        saveMembership(revocationCopy);

        staleActivationCopy.activate(createdAt.plusSeconds(180));

        assertThatThrownBy(() -> saveMembership(staleActivationCopy))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(findMembership(tenant.id(), identityId).orElseThrow().status())
                .isEqualTo(TenantMembershipStatus.REVOKED);
    }

    @Test
    void rejectsStaleTenantUpdatesAfterArchival() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = Tenant.create(TenantId.random(), "Workspace", createdAt);
        tenant.suspend(createdAt.plusSeconds(60));
        tenantRepository.save(tenant);

        Tenant archivalCopy = tenantRepository.findById(tenant.id()).orElseThrow();
        Tenant staleActivationCopy = tenantRepository.findById(tenant.id()).orElseThrow();

        archivalCopy.archive(createdAt.plusSeconds(120));
        tenantRepository.save(archivalCopy);

        staleActivationCopy.activate(createdAt.plusSeconds(180));

        assertThatThrownBy(() -> tenantRepository.save(staleActivationCopy))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(tenantRepository.findById(tenant.id()).orElseThrow().status()).isEqualTo(TenantStatus.ARCHIVED);
    }

    @Test
    void enforcesTenantIsolationAtTheDatabaseLayerWithRowLevelSecurity() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        Tenant tenantB = persistTenant("Tenant B", createdAt);

        IdentityId identityA = new IdentityId(UUID.randomUUID());
        IdentityId identityB = new IdentityId(UUID.randomUUID());

        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        TenantMembership membershipB = TenantMembership.createActive(
                TenantMembershipId.random(), tenantB.id(), identityB, TenantRole.OPERATOR, createdAt
        );

        saveMembership(membershipA);
        saveMembership(membershipB);

        // Within Tenant A context:
        tenantContextProvider.runWithTenantId(tenantA.id(), () -> {
            // 1. SELECT query deliberately omitting WHERE tenant_id clause returns ONLY Tenant A rows
            List<UUID> visibleIds = jdbcTemplate.queryForList("SELECT id FROM tenant_memberships", UUID.class);
            assertThat(visibleIds).containsExactly(membershipA.id().value());

            // 2. Cannot find Tenant B membership via repository lookup
            assertThat(membershipRepository.findByTenantIdAndIdentityId(tenantB.id(), identityB)).isEmpty();

            // 3. Unscoped UPDATE affects only Tenant A rows
            int updatedRows = jdbcTemplate.update("UPDATE tenant_memberships SET role = 'VIEWER'");
            assertThat(updatedRows).isEqualTo(1);

            // 4. Unscoped DELETE affects only Tenant A rows
            int deletedRows = jdbcTemplate.update("DELETE FROM tenant_memberships");
            assertThat(deletedRows).isEqualTo(1);
        });

        // Within Tenant B context: verify Tenant B membership was completely unaffected by Tenant A's operations
        tenantContextProvider.runWithTenantId(tenantB.id(), () -> {
            TenantMembership restoredB = findMembership(tenantB.id(), identityB).orElseThrow();
            assertThat(restoredB.role()).isEqualTo(TenantRole.OPERATOR);
            assertThat(restoredB.id()).isEqualTo(membershipB.id());

            List<UUID> visibleIdsB = jdbcTemplate.queryForList("SELECT id FROM tenant_memberships", UUID.class);
            assertThat(visibleIdsB).containsExactly(membershipB.id().value());
        });
    }

    @Test
    void blocksCrossTenantInsertsAndReassignmentsAtTheDatabaseLayer() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        Tenant tenantB = persistTenant("Tenant B", createdAt);

        IdentityId identityA = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        saveMembership(membershipA);

        tenantContextProvider.runWithTenantId(tenantA.id(), () -> {
            // Attempt to insert row for Tenant B while in Tenant A context fails closed via RLS WITH CHECK
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO tenant_memberships (id, tenant_id, identity_id, role, status, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenantB.id().value(), UUID.randomUUID(), "OPERATOR", "ACTIVE",
                    Timestamp.from(createdAt), Timestamp.from(createdAt), 0
            ))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("row-level security policy");

            // Attempt to reassign Tenant A membership to Tenant B fails closed via RLS WITH CHECK
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE tenant_memberships SET tenant_id = ? WHERE id = ?",
                    tenantB.id().value(), membershipA.id().value()
            ))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("row-level security policy");
        });
    }

    @Test
    void failsClosedWhenDatabaseTenantContextIsAbsent() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = persistTenant("Workspace", createdAt);
        IdentityId identity = new IdentityId(UUID.randomUUID());
        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), identity, TenantRole.ADMIN, createdAt
        );
        saveMembership(membership);

        // Outside of any tenant context:
        assertThat(tenantContextProvider.currentTenantId()).isEmpty();

        // 1. Reading tenant-scoped table returns 0 rows (fail closed)
        List<UUID> visibleIds = jdbcTemplate.queryForList("SELECT id FROM tenant_memberships", UUID.class);
        assertThat(visibleIds).isEmpty();

        // 2. Repository query returns empty
        assertThat(membershipRepository.findByTenantIdAndIdentityId(tenant.id(), identity)).isEmpty();

        // 3. Inserting without tenant context fails closed
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO tenant_memberships (id, tenant_id, identity_id, role, status, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), tenant.id().value(), UUID.randomUUID(), "OPERATOR", "ACTIVE",
                Timestamp.from(createdAt), Timestamp.from(createdAt), 0
        ))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security policy");
    }

    @Test
    void clearsSessionTenantSettingWhenEnteringTransactionsAndFailsClosedAfterward() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        saveMembership(membershipA);

        // Step 1: Run in auto-commit mode under Tenant A
        tenantContextProvider.runWithTenantId(tenantA.id(), () -> {
            List<UUID> ids = jdbcTemplate.queryForList("SELECT id FROM tenant_memberships", UUID.class);
            assertThat(ids).containsExactly(membershipA.id().value());
        });

        // Step 2: Run in transaction mode under Tenant A and commit
        tenantContextProvider.runWithTenantId(tenantA.id(), () -> {
            executeInTransaction(() -> {
                List<UUID> ids = jdbcTemplate.queryForList("SELECT id FROM tenant_memberships", UUID.class);
                assertThat(ids).containsExactly(membershipA.id().value());
            });
        });

        // Step 3: Run without tenant context in auto-commit mode -> must fail closed (0 rows, no leakage)
        List<UUID> unscopedIds = jdbcTemplate.queryForList("SELECT id FROM tenant_memberships", UUID.class);
        assertThat(unscopedIds).isEmpty();
    }

    @Test
    void clearsRestoredSessionTenantSettingAfterTransactionRollbackAndFailsClosed() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        saveMembership(membershipA);

        // Step 1: Run in auto-commit mode under Tenant A (sets session-level setting)
        tenantContextProvider.runWithTenantId(tenantA.id(), () -> {
            List<UUID> ids = jdbcTemplate.queryForList("SELECT id FROM tenant_memberships", UUID.class);
            assertThat(ids).containsExactly(membershipA.id().value());
        });

        // Step 2: Run in transaction mode under Tenant A and roll back
        tenantContextProvider.runWithTenantId(tenantA.id(), () -> {
            executeAndRollBack(() -> {
                List<UUID> ids = jdbcTemplate.queryForList("SELECT id FROM tenant_memberships", UUID.class);
                assertThat(ids).containsExactly(membershipA.id().value());
            });
        });

        // Step 3: Run without tenant context in auto-commit mode -> must fail closed (0 rows, no leakage)
        List<UUID> unscopedIds = jdbcTemplate.queryForList("SELECT id FROM tenant_memberships", UUID.class);
        assertThat(unscopedIds).isEmpty();
    }

    @Test
    void allowsTransactionConfigurationAfterSetAutoCommitFalse() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        saveMembership(membershipA);

        tenantContextProvider.runWithTenantId(tenantA.id(), () -> {
            try (Connection connection = dataSource.getConnection()) {
                // Disable auto-commit then configure transaction state before any statement
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setReadOnly(true);

                try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM tenant_memberships")) {
                    try (ResultSet rs = statement.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat((UUID) rs.getObject("id")).isEqualTo(membershipA.id().value());
                    }
                }
                connection.rollback();
            } catch (SQLException exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    @Test
    void clearsSessionStateWhenConnectionClosedWithOpenTransactionAndFailsClosed() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        saveMembership(membershipA);

        // Checkout connection under Tenant A in auto-commit mode, then set autoCommit(false) and close without committing
        tenantContextProvider.runWithTenantId(tenantA.id(), () -> {
            try (Connection connection = dataSource.getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM tenant_memberships")) {
                    try (ResultSet rs = statement.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                    }
                }
                connection.setAutoCommit(false);
            } catch (SQLException exception) {
                throw new RuntimeException(exception);
            }
        });

        // Unscoped checkout must fail closed
        List<UUID> unscopedIds = jdbcTemplate.queryForList("SELECT id FROM tenant_memberships", UUID.class);
        assertThat(unscopedIds).isEmpty();
    }

    @Test
    void reappliesTenantContextWhenPreparedStatementExecutedAcrossScopes() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        Tenant tenantB = persistTenant("Tenant B", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        IdentityId identityB = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        TenantMembership membershipB = TenantMembership.createActive(
                TenantMembershipId.random(), tenantB.id(), identityB, TenantRole.OPERATOR, createdAt
        );
        saveMembership(membershipA);
        saveMembership(membershipB);

        try (Connection connection = dataSource.getConnection()) {
            // Prepare statement under Tenant A scope
            PreparedStatement statement = tenantContextProvider.callWithTenantId(
                    tenantA.id(),
                    () -> connection.prepareStatement("SELECT id FROM tenant_memberships")
            );

            // Execute statement under Tenant B scope -> must return Tenant B row
            tenantContextProvider.runWithTenantId(tenantB.id(), () -> {
                try (ResultSet rs = statement.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat((UUID) rs.getObject("id")).isEqualTo(membershipB.id().value());
                    assertThat(rs.next()).isFalse();
                } catch (SQLException exception) {
                    throw new RuntimeException(exception);
                }
            });

            // Execute statement outside any tenant scope -> must return 0 rows (fail closed)
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isFalse();
            }

            statement.close();
        }
    }

    @Test
    void permitsMigrationRoleToManageTenantMembershipsUnderForcedRls() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = persistTenant("Migration Workspace", createdAt);
        UUID membershipId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();

        // Connect directly as dokene_migration role
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), MIGRATION_ROLE, MIGRATION_PASSWORD)) {
            // Insert membership without a runtime signed context (permitted by migration policy)
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO dokene.tenant_memberships (id, tenant_id, identity_id, role, status, created_at, updated_at, version)
                    VALUES (?, ?, ?, 'ADMIN', 'ACTIVE', ?, ?, 0)
                    """)) {
                insert.setObject(1, membershipId);
                insert.setObject(2, tenant.id().value());
                insert.setObject(3, identityId);
                insert.setTimestamp(4, Timestamp.from(createdAt));
                insert.setTimestamp(5, Timestamp.from(createdAt));
                int rows = insert.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }

            // Read membership as dokene_migration without a runtime signed context
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM dokene.tenant_memberships WHERE id = ?")) {
                select.setObject(1, membershipId);
                try (ResultSet rs = select.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat((UUID) rs.getObject("id")).isEqualTo(membershipId);
                }
            }
        }
    }

    @Test
    void clearsInheritedSessionTenantWhenEnteringUnscopedTransactionAndFailsClosed() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        saveMembership(membershipA);

        try (Connection connection = dataSource.getConnection()) {
            // Step 1: Execute in auto-commit mode under Tenant A -> establishes session-level setting A
            tenantContextProvider.runWithTenantId(tenantA.id(), () -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM tenant_memberships")) {
                    try (ResultSet rs = statement.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat((UUID) rs.getObject("id")).isEqualTo(membershipA.id().value());
                    }
                } catch (SQLException exception) {
                    throw new RuntimeException(exception);
                }
            });

            // Step 2: Outside Tenant A scope, start a transaction and execute a query -> must fail closed (0 rows)
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM tenant_memberships")) {
                try (ResultSet rs = statement.executeQuery()) {
                    assertThat(rs.next()).isFalse();
                }
            }
            connection.rollback();
        }
    }

    @Test
    void statementGetConnectionReturnsDecoratedConnectionProxy() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        Tenant tenantB = persistTenant("Tenant B", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        IdentityId identityB = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        TenantMembership membershipB = TenantMembership.createActive(
                TenantMembershipId.random(), tenantB.id(), identityB, TenantRole.OPERATOR, createdAt
        );
        saveMembership(membershipA);
        saveMembership(membershipB);

        try (Connection originalConnection = dataSource.getConnection()) {
            try (PreparedStatement statement = originalConnection.prepareStatement("SELECT id FROM tenant_memberships")) {
                Connection statementConnection = statement.getConnection();
                assertThat(statementConnection).isNotNull();
                assertThat(statementConnection.toString()).contains("TenantAwareConnectionProxy");

                // Using the statement's connection accessor under Tenant B applies Tenant B context
                tenantContextProvider.runWithTenantId(tenantB.id(), () -> {
                    try (PreparedStatement statementFromAccessor = statementConnection.prepareStatement("SELECT id FROM tenant_memberships")) {
                        try (ResultSet rs = statementFromAccessor.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                            assertThat((UUID) rs.getObject("id")).isEqualTo(membershipB.id().value());
                        }
                    } catch (SQLException exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
    }

    @Test
    void resultSetGetStatementReturnsDecoratedStatementProxy() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        Tenant tenantB = persistTenant("Tenant B", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        IdentityId identityB = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        TenantMembership membershipB = TenantMembership.createActive(
                TenantMembershipId.random(), tenantB.id(), identityB, TenantRole.OPERATOR, createdAt
        );
        saveMembership(membershipA);
        saveMembership(membershipB);

        try (Connection connection = dataSource.getConnection()) {
            // Execute under Tenant A and get ResultSet
            ResultSet resultSet = tenantContextProvider.callWithTenantId(tenantA.id(), () -> {
                Statement statement = connection.createStatement();
                ResultSet resultSetFromTenantA = statement.executeQuery("SELECT id FROM tenant_memberships");
                assertThat(resultSetFromTenantA.next()).isTrue();
                assertThat((UUID) resultSetFromTenantA.getObject("id")).isEqualTo(membershipA.id().value());
                return resultSetFromTenantA;
            });

            // Retrieve statement from ResultSet
            Statement statementFromResultSet = resultSet.getStatement();
            assertThat(statementFromResultSet).isNotNull();
            assertThat(statementFromResultSet.toString()).contains("TenantAwareStatementProxy");

            // Execute query on the retrieved statement under Tenant B -> applies Tenant B context
            tenantContextProvider.runWithTenantId(tenantB.id(), () -> {
                try (ResultSet rsB = statementFromResultSet.executeQuery("SELECT id FROM tenant_memberships")) {
                    assertThat(rsB.next()).isTrue();
                    assertThat((UUID) rsB.getObject("id")).isEqualTo(membershipB.id().value());
                } catch (SQLException exception) {
                    throw new RuntimeException(exception);
                }
            });

            resultSet.close();
            statementFromResultSet.close();
        }
    }

    @Test
    void databaseMetaDataGetConnectionReturnsDecoratedConnectionProxy() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        Tenant tenantB = persistTenant("Tenant B", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        IdentityId identityB = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        TenantMembership membershipB = TenantMembership.createActive(
                TenantMembershipId.random(), tenantB.id(), identityB, TenantRole.OPERATOR, createdAt
        );
        saveMembership(membershipA);
        saveMembership(membershipB);

        try (Connection connection = dataSource.getConnection()) {
            tenantContextProvider.runWithTenantId(tenantA.id(), () -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM tenant_memberships")) {
                    try (ResultSet rs = statement.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat((UUID) rs.getObject("id")).isEqualTo(membershipA.id().value());
                    }
                } catch (SQLException exception) {
                    throw new RuntimeException(exception);
                }
            });

            DatabaseMetaData metadata = connection.getMetaData();
            Connection metadataConnection = metadata.getConnection();
            assertThat(metadataConnection).isNotNull();
            assertThat(metadataConnection.toString()).contains("TenantAwareConnectionProxy");

            tenantContextProvider.runWithTenantId(tenantB.id(), () -> {
                try (PreparedStatement statement = metadataConnection.prepareStatement("SELECT id FROM tenant_memberships")) {
                    try (ResultSet rs = statement.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat((UUID) rs.getObject("id")).isEqualTo(membershipB.id().value());
                        assertThat(rs.next()).isFalse();
                    }
                } catch (SQLException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }

    @Test
    void rejectsUpdatableResultSetOperationsAfterTenantScopeChanges() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        Tenant tenantB = persistTenant("Tenant B", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        IdentityId identityB = new IdentityId(UUID.randomUUID());
        IdentityId identityC = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        TenantMembership membershipB = TenantMembership.createActive(
                TenantMembershipId.random(), tenantB.id(), identityB, TenantRole.OPERATOR, createdAt
        );
        saveMembership(membershipA);
        saveMembership(membershipB);
        UUID membershipCId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE)) {
            ResultSet resultSet = tenantContextProvider.callWithTenantId(
                    tenantA.id(),
                    () -> statement.executeQuery("SELECT id, role FROM tenant_memberships")
            );
            try (resultSet) {
                tenantContextProvider.callWithTenantId(tenantA.id(), () -> {
                    assertThat(resultSet.next()).isTrue();
                    assertThat((UUID) resultSet.getObject("id")).isEqualTo(membershipA.id().value());
                    return null;
                });
                assertThatThrownBy(() -> tenantContextProvider.callWithTenantId(tenantB.id(), () -> {
                    resultSet.refreshRow();
                    return null;
                })).isInstanceOf(SQLException.class)
                        .hasMessageContaining("tenant context changes");
                assertThatThrownBy(() -> tenantContextProvider.callWithTenantId(tenantB.id(), () -> {
                    resultSet.updateString("role", TenantRole.OPERATOR.name());
                    return null;
                })).isInstanceOf(SQLException.class)
                        .hasMessageContaining("tenant context changes");
                assertThatThrownBy(resultSet::next)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("tenant context changes");
            }

            try (ResultSet insertResultSet = tenantContextProvider.callWithTenantId(
                    tenantB.id(),
                    () -> statement.executeQuery("""
                            SELECT id, tenant_id, identity_id, role, status, created_at, updated_at, version
                            FROM tenant_memberships
                            """))) {
                tenantContextProvider.callWithTenantId(tenantB.id(), () -> {
                    assertThat(insertResultSet.next()).isTrue();
                    insertResultSet.moveToInsertRow();
                    insertResultSet.updateObject("id", membershipCId);
                    insertResultSet.updateObject("tenant_id", tenantB.id().value());
                    insertResultSet.updateObject("identity_id", identityC.value());
                    insertResultSet.updateString("role", TenantRole.OPERATOR.name());
                    insertResultSet.updateString("status", TenantMembershipStatus.ACTIVE.name());
                    insertResultSet.updateTimestamp("created_at", Timestamp.from(createdAt));
                    insertResultSet.updateTimestamp("updated_at", Timestamp.from(createdAt));
                    insertResultSet.updateLong("version", 0L);
                    insertResultSet.insertRow();
                    insertResultSet.moveToCurrentRow();
                    return null;
                });
            }
        }

        TenantMembership persistedMembershipA = findMembership(tenantA.id(), identityA).orElseThrow();
        assertThat(persistedMembershipA.role()).isEqualTo(TenantRole.ADMIN);
        TenantMembership persistedMembershipC = findMembership(tenantB.id(), identityC).orElseThrow();
        assertThat(persistedMembershipC.id().value()).isEqualTo(membershipCId);
    }

    @Test
    void rejectsRawSqlTransactionControlCommands() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> connection.prepareStatement("/* transaction */ COMMIT"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Raw SQL transaction control");
            assertThatThrownBy(() -> statement.execute("-- transaction\nROLLBACK"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Raw SQL transaction control");
            assertThatThrownBy(() -> statement.addBatch("SELECT 1; /* transaction */ SAVEPOINT tenant_scope"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Raw SQL transaction control");
            assertThatThrownBy(() -> statement.execute("PREPARE TRANSACTION 'tenant_scope'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Raw SQL transaction control");
        }
    }

    @Test
    void rejectsForgedAndExpiredSignedTenantContexts() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Tenant A", createdAt);
        Tenant tenantB = persistTenant("Tenant B", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        IdentityId identityB = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        TenantMembership membershipB = TenantMembership.createActive(
                TenantMembershipId.random(), tenantB.id(), identityB, TenantRole.ADMIN, createdAt
        );
        saveMembership(membershipA);
        saveMembership(membershipB);

        SignedDatabaseContext tenantAContext = databaseContextSigner.issueTenantContext(tenantA.id());
        String forgedTenantBPayload = tenantAContext.payload().replace(
                tenantA.id().value().toString(), tenantB.id().value().toString()
        );
        assertThat(readMembershipIdsAsRuntime(forgedTenantBPayload, tenantAContext.signature())).isEmpty();

        HmacDatabaseContextSigner expiredSigner = new HmacDatabaseContextSigner(
                TENANT_CONTEXT_SIGNING_KEY,
                Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), java.time.ZoneOffset.UTC),
                new SecureRandom()
        );
        SignedDatabaseContext expiredContext = expiredSigner.issueTenantContext(tenantB.id());
        assertThat(readMembershipIdsAsRuntime(expiredContext.payload(), expiredContext.signature())).isEmpty();
    }

    @Test
    void discoversOnlyActiveMembershipsForTheSignedIdentity() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Alpha workspace", createdAt);
        Tenant tenantB = persistTenant("Beta workspace", createdAt);
        Tenant tenantC = persistTenant("Gamma workspace", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        IdentityId identityB = new IdentityId(UUID.randomUUID());
        saveMembership(TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        ));
        saveMembership(TenantMembership.createActive(
                TenantMembershipId.random(), tenantB.id(), identityA, TenantRole.VIEWER, createdAt
        ));
        saveMembership(TenantMembership.createActive(
                TenantMembershipId.random(), tenantC.id(), identityB, TenantRole.ADMIN, createdAt
        ));

        assertThat(tenantMembershipDiscovery.findActiveMemberships(identityA))
                .extracting(TenantMembershipDiscovery.ActiveTenantMembership::tenantId)
                .containsExactly(tenantA.id(), tenantB.id());

        SignedDatabaseContext identityAContext = databaseContextSigner.issueIdentityContext(identityA);
        String forgedIdentityBPayload = identityAContext.payload().replace(
                identityA.value().toString(), identityB.value().toString()
        );
        assertThat(discoverTenantIdsAsRuntime(forgedIdentityBPayload, identityAContext.signature())).isEmpty();
    }

    @Test
    void preventsUnwrapFromExposingPhysicalJdbcObjects() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertDecoratorUnwrap(connection, Connection.class);
            assertVendorUnwrapRejected(connection, "org.postgresql.PGConnection");

            DatabaseMetaData metadata = connection.getMetaData();
            assertDecoratorUnwrap(metadata, DatabaseMetaData.class);
            assertVendorUnwrapRejected(metadata, "org.postgresql.jdbc.PgDatabaseMetaData");
            try (ResultSet metadataResultSet = metadata.getTables(null, null, null, null)) {
                assertDecoratorUnwrap(metadataResultSet, ResultSet.class);
                assertVendorUnwrapRejected(metadataResultSet, "org.postgresql.jdbc.PgResultSet");
                assertThat(metadataResultSet.getStatement()).isNull();
            }

            try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                assertDecoratorUnwrap(statement, Statement.class);
                assertVendorUnwrapRejected(statement, "org.postgresql.PGStatement");
                assertDecoratorUnwrap(resultSet, ResultSet.class);
                assertVendorUnwrapRejected(resultSet, "org.postgresql.jdbc.PgResultSet");
            }
        }
    }

    @Test
    void supportsZeroDowntimeSigningKeyRotation() throws SQLException {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenantA = persistTenant("Rotation Workspace", createdAt);
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        TenantMembership membershipA = TenantMembership.createActive(
                TenantMembershipId.random(), tenantA.id(), identityA, TenantRole.ADMIN, createdAt
        );
        saveMembership(membershipA);

        // 1. Initial capabilities issued with default key succeed
        SignedDatabaseContext contextK1 = databaseContextSigner.issueTenantContext(tenantA.id());
        assertThat(readMembershipIdsAsRuntime(contextK1.payload(), contextK1.signature()))
                .containsExactly(membershipA.id().value());

        // 2. Provision new key 'k2' in dokene.tenant_context_signing_keys as migration role
        String key2Hex = randomSigningKey();
        executeAsMigration("INSERT INTO dokene.tenant_context_signing_keys (key_id, signing_key, status) VALUES ('k2', decode('" + key2Hex + "', 'hex'), 'ACTIVE')");

        // 3. Create a signer for key 'k2'
        HmacDatabaseContextSigner signerK2 = new HmacDatabaseContextSigner(key2Hex, "k2", Clock.systemUTC(), new SecureRandom());
        SignedDatabaseContext contextK2 = signerK2.issueTenantContext(tenantA.id());

        // 4. Both K1 and K2 capabilities are valid during rotation window
        assertThat(readMembershipIdsAsRuntime(contextK1.payload(), contextK1.signature()))
                .containsExactly(membershipA.id().value());
        assertThat(readMembershipIdsAsRuntime(contextK2.payload(), contextK2.signature()))
                .containsExactly(membershipA.id().value());

        // 5. Retire K1 key as migration role
        executeAsMigration("UPDATE dokene.tenant_context_signing_keys SET status = 'RETIRED', retired_at = statement_timestamp() WHERE key_id = 'default'");

        // 6. Capabilities issued with K1 now fail closed
        SignedDatabaseContext contextK1AfterRetire = databaseContextSigner.issueTenantContext(tenantA.id());
        assertThat(readMembershipIdsAsRuntime(contextK1AfterRetire.payload(), contextK1AfterRetire.signature()))
                .isEmpty();

        // 7. Capabilities issued with active K2 key continue to succeed
        assertThat(readMembershipIdsAsRuntime(contextK2.payload(), contextK2.signature()))
                .containsExactly(membershipA.id().value());

        // Restore K1 for subsequent tests
        executeAsMigration("UPDATE dokene.tenant_context_signing_keys SET status = 'ACTIVE', retired_at = NULL WHERE key_id = 'default'");
        executeAsMigration("DELETE FROM dokene.tenant_context_signing_keys WHERE key_id = 'k2'");
    }

    private static <T> void assertDecoratorUnwrap(Wrapper wrapper, Class<T> type) throws SQLException {
        assertThat(wrapper.isWrapperFor(type)).isTrue();
        assertThat(wrapper.unwrap(type)).isSameAs(wrapper);
    }

    private static void assertVendorUnwrapRejected(Wrapper wrapper, String vendorClassName)
            throws ClassNotFoundException, SQLException {
        Class<?> vendorType = Class.forName(vendorClassName);
        assertThat(wrapper.isWrapperFor(vendorType)).isFalse();
        assertThatThrownBy(() -> wrapper.unwrap(vendorType))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("cannot expose");
    }

    private Tenant persistTenant(String displayName, Instant createdAt) {
        Tenant tenant = Tenant.create(TenantId.random(), displayName, createdAt);
        tenantRepository.save(tenant);
        return tenant;
    }

    private TenantMembership saveMembership(TenantMembership membership) {
        return tenantContextProvider.callWithTenantId(membership.tenantId(), () -> membershipRepository.save(membership));
    }

    private Optional<TenantMembership> findMembership(TenantId tenantId, IdentityId identityId) {
        return tenantContextProvider.callWithTenantId(
                tenantId,
                () -> membershipRepository.findByTenantIdAndIdentityId(tenantId, identityId)
        );
    }

    private List<UUID> readMembershipIdsAsRuntime(String contextPayload, String contextSignature) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
                PreparedStatement setContext = connection.prepareStatement(
                        "SELECT set_config(?, ?, false), set_config(?, ?, false)")) {
            setContext.setString(1, "dokene.tenant_context");
            setContext.setString(2, contextPayload);
            setContext.setString(3, "dokene.tenant_context_signature");
            setContext.setString(4, contextSignature);
            setContext.execute();
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT id FROM dokene.tenant_memberships ORDER BY id")) {
                List<UUID> ids = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    ids.add(resultSet.getObject("id", UUID.class));
                }
                return ids;
            }
        }
    }

    private List<UUID> discoverTenantIdsAsRuntime(String contextPayload, String contextSignature) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT tenant_id
                        FROM dokene.discover_active_tenant_memberships(?, ?)
                        """)) {
            statement.setString(1, contextPayload);
            statement.setString(2, contextSignature);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<UUID> ids = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    ids.add(resultSet.getObject("tenant_id", UUID.class));
                }
                return ids;
            }
        }
    }

    private void executeAndRollBack(Runnable action) {
        executeInTransaction(status -> {
            action.run();
            status.setRollbackOnly();
        });
    }

    private void executeInTransaction(Runnable action) {
        executeInTransaction(status -> action.run());
    }

    private void executeInTransaction(Consumer<TransactionStatus> action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(action::accept);
    }

    private void executeRequiresNew(Runnable action) {
        requiresNewTransaction().executeWithoutResult(status -> action.run());
    }

    private void executeRequiresNewAndRollBack(Runnable action) {
        requiresNewTransaction().executeWithoutResult(status -> {
            action.run();
            status.setRollbackOnly();
        });
    }

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate;
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

    private String tableOwner(String tableName) {
        return jdbcTemplate.queryForObject(
                "SELECT tableowner FROM pg_tables WHERE schemaname = 'dokene' AND tablename = ?",
                String.class,
                tableName
        );
    }

    private void executeAsRuntime(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void executeAsMigration(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATION_ROLE, MIGRATION_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
