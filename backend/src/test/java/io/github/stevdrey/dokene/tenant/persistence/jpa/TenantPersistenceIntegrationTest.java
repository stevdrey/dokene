package io.github.stevdrey.dokene.tenant.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.domain.TenantStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
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

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JpaTenantRepositoryAdapter tenantRepository;

    @Autowired
    private JpaTenantMembershipRepositoryAdapter membershipRepository;

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
    }

    @Test
    void migratesTheTenantFoundationWithLeastPrivilegeRuntimeAccess() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'dokene' ORDER BY table_name",
                String.class
        )).containsExactly("tenant_memberships", "tenants");
        assertThat(tableOwner("tenants")).isEqualTo(MIGRATION_ROLE);
        assertThat(tableOwner("tenant_memberships")).isEqualTo(MIGRATION_ROLE);

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

        assertThatThrownBy(() -> executeAsRuntime("CREATE TABLE dokene.runtime_ddl_denied (id INTEGER)"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> executeAsRuntime("ALTER TABLE dokene.tenants ADD COLUMN runtime_ddl_denied BOOLEAN"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> executeAsRuntime("DROP TABLE dokene.tenants"))
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
        TenantMembership persistedMembership = membershipRepository.save(membership);

        Tenant restoredTenant = tenantRepository.findById(tenant.id()).orElseThrow();
        TenantMembership restoredMembership = membershipRepository
                .findByTenantIdAndIdentityId(tenant.id(), membership.identityId())
                .orElseThrow();

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

        membershipRepository.save(TenantMembership.createActive(
                TenantMembershipId.random(), firstTenant.id(), identityId, TenantRole.OPERATOR, createdAt
        ));
        membershipRepository.save(TenantMembership.createActive(
                TenantMembershipId.random(), secondTenant.id(), identityId, TenantRole.OPERATOR, createdAt
        ));

        assertThat(membershipRepository.findByTenantIdAndIdentityId(firstTenant.id(), identityId)).isPresent();
        assertThat(membershipRepository.findByTenantIdAndIdentityId(secondTenant.id(), identityId)).isPresent();
    }

    @Test
    void rejectsDuplicateMembershipsForTheSameTenantAndIdentity() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = persistTenant("Workspace", createdAt);
        IdentityId identityId = new IdentityId(UUID.randomUUID());

        membershipRepository.save(TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), identityId, TenantRole.OPERATOR, createdAt
        ));

        assertThatThrownBy(() -> membershipRepository.save(TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), identityId, TenantRole.VIEWER, createdAt
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMembershipsForUnknownTenants() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");

        assertThatThrownBy(() -> membershipRepository.save(TenantMembership.createActive(
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
        TenantMembership persistedMembership = membershipRepository.save(membership);

        Tenant restoredTenant = tenantRepository.findById(tenant.id()).orElseThrow();
        TenantMembership restoredMembership = membershipRepository
                .findByTenantIdAndIdentityId(tenant.id(), membership.identityId())
                .orElseThrow();

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

        membershipRepository.save(membership);
        assertThat(membership.revision()).hasValue(0);

        membership.suspend(createdAt.plusSeconds(60));
        membershipRepository.save(membership);
        assertThat(membership.revision()).hasValue(1);

        membership.revoke(createdAt.plusSeconds(120));
        membershipRepository.save(membership);

        assertThat(membership.revision()).hasValue(2);
        assertThat(membershipRepository.findByTenantIdAndIdentityId(tenant.id(), membership.identityId()).orElseThrow().status())
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
        membershipRepository.save(membership);

        membership.changeRole(TenantRole.OPERATOR, createdAt.plusSeconds(60));
        membershipRepository.save(membership);

        TenantMembership winningCopy = membershipRepository
                .findByTenantIdAndIdentityId(tenant.id(), identityId)
                .orElseThrow();
        TenantMembership staleCopy = membershipRepository
                .findByTenantIdAndIdentityId(tenant.id(), identityId)
                .orElseThrow();

        winningCopy.changeRole(TenantRole.ADMIN, createdAt.plusSeconds(120));
        membershipRepository.save(winningCopy);

        staleCopy.changeRole(TenantRole.OWNER, createdAt.plusSeconds(180));

        assertThatThrownBy(() -> membershipRepository.save(staleCopy))
                .isInstanceOf(OptimisticLockingFailureException.class);

        TenantMembership restoredMembership = membershipRepository
                .findByTenantIdAndIdentityId(tenant.id(), identityId)
                .orElseThrow();
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
        membershipRepository.save(existingMembership);
        TenantMembership newMembership = TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), new IdentityId(UUID.randomUUID()), TenantRole.VIEWER, createdAt
        );

        executeAndRollBack(() -> {
            existingMembership.suspend(createdAt.plusSeconds(60));
            membershipRepository.save(existingMembership);
            assertThat(existingMembership.revision()).hasValue(1);

            existingMembership.activate(createdAt.plusSeconds(120));
            membershipRepository.save(existingMembership);
            assertThat(existingMembership.revision()).hasValue(2);

            membershipRepository.save(newMembership);
            assertThat(newMembership.revision()).hasValue(0);
        });

        assertThat(existingMembership.revision()).hasValue(0);
        assertThat(newMembership.revision()).isEmpty();
        assertThat(membershipRepository.findByTenantIdAndIdentityId(tenant.id(), existingMembership.identityId()).orElseThrow().status())
                .isEqualTo(TenantMembershipStatus.ACTIVE);
        assertThat(membershipRepository.findByTenantIdAndIdentityId(tenant.id(), newMembership.identityId())).isEmpty();

        existingMembership.suspend(createdAt.plusSeconds(180));
        membershipRepository.save(existingMembership);
        membershipRepository.save(newMembership);

        assertThat(existingMembership.revision()).hasValue(1);
        assertThat(newMembership.revision()).hasValue(0);
        assertThat(membershipRepository.findByTenantIdAndIdentityId(tenant.id(), existingMembership.identityId()).orElseThrow().status())
                .isEqualTo(TenantMembershipStatus.SUSPENDED);
        assertThat(membershipRepository.findByTenantIdAndIdentityId(tenant.id(), newMembership.identityId())).isPresent();
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
                membershipRepository.save(rolledBackMembership);
                assertThat(rolledBackMembership.revision()).hasValue(0);
            });

            assertThat(rolledBackMembership.revision()).isEmpty();
        });

        assertThat(outerTenant.revision()).hasValue(1);
        assertThat(tenantRepository.findById(outerTenant.id()).orElseThrow().status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(membershipRepository.findByTenantIdAndIdentityId(outerTenant.id(), rolledBackMembership.identityId())).isEmpty();

        membershipRepository.save(rolledBackMembership);

        assertThat(rolledBackMembership.revision()).hasValue(0);
        assertThat(membershipRepository.findByTenantIdAndIdentityId(outerTenant.id(), rolledBackMembership.identityId())).isPresent();
    }

    @Test
    void retainsInnerTenantRevisionWhenOuterTransactionRollsBack() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant membershipTenant = persistTenant("Membership workspace", createdAt);
        TenantMembership outerMembership = TenantMembership.createActive(
                TenantMembershipId.random(), membershipTenant.id(), new IdentityId(UUID.randomUUID()), TenantRole.OPERATOR, createdAt
        );
        membershipRepository.save(outerMembership);
        Tenant innerTenant = persistTenant("Inner workspace", createdAt);

        executeAndRollBack(() -> {
            outerMembership.suspend(createdAt.plusSeconds(60));
            membershipRepository.save(outerMembership);
            assertThat(outerMembership.revision()).hasValue(1);

            executeRequiresNew(() -> {
                innerTenant.suspend(createdAt.plusSeconds(60));
                tenantRepository.save(innerTenant);
                assertThat(innerTenant.revision()).hasValue(1);
            });

            assertThat(innerTenant.revision()).hasValue(1);
        });

        assertThat(outerMembership.revision()).hasValue(0);
        assertThat(membershipRepository.findByTenantIdAndIdentityId(membershipTenant.id(), outerMembership.identityId()).orElseThrow().status())
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
        membershipRepository.save(membership);

        TenantMembership revocationCopy = membershipRepository
                .findByTenantIdAndIdentityId(tenant.id(), identityId)
                .orElseThrow();
        TenantMembership staleActivationCopy = membershipRepository
                .findByTenantIdAndIdentityId(tenant.id(), identityId)
                .orElseThrow();

        revocationCopy.revoke(createdAt.plusSeconds(120));
        membershipRepository.save(revocationCopy);

        staleActivationCopy.activate(createdAt.plusSeconds(180));

        assertThatThrownBy(() -> membershipRepository.save(staleActivationCopy))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(membershipRepository.findByTenantIdAndIdentityId(tenant.id(), identityId).orElseThrow().status())
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

    private Tenant persistTenant(String displayName, Instant createdAt) {
        Tenant tenant = Tenant.create(TenantId.random(), displayName, createdAt);
        tenantRepository.save(tenant);
        return tenant;
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
