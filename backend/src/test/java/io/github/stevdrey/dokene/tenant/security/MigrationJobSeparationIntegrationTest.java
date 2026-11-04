// ABOUTME: Proves the migration/runtime split of issue 308 (audit M4): the one-shot job migrates clean and older
// ABOUTME: schemas as dokene_migration, and the runtime role can only validate the schema, never apply it.
package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.migration.DokeneMigrationApplication;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.core.internal.exception.FlywaySqlException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class MigrationJobSeparationIntegrationTest {
    private static final String PREVIOUS_RELEASE_VERSION = "33";

    @Test
    void migratesACleanDatabaseAndProvisionsTheSigningKey() throws Exception {
        String url = emptyDatabase("migration_job_clean");

        try (ConfigurableApplicationContext job = DokeneMigrationApplication.application().run(jobArguments(url))) {
            assertThat(job.getBeanNamesForType(DataSource.class)).as("no runtime datasource in the job").isEmpty();
            try (Connection connection = job.getBean(Flyway.class).getConfiguration().getDataSource().getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet user = statement.executeQuery("SELECT current_user")) {
                assertThat(user.next()).isTrue();
                assertThat(user.getString(1)).isEqualTo(TenantSecurityIntegrationFixture.MIGRATION_ROLE);
            }
        }

        assertThat(runtimeFlyway(url).info().pending()).isEmpty();
        runtimeFlyway(url).validate();
        try (Connection migration = migrationConnection(url); Statement statement = migration.createStatement();
                ResultSet key = statement.executeQuery(
                        "SELECT encode(signing_key, 'hex'), status FROM dokene.tenant_context_signing_keys WHERE key_id = 'default'")) {
            assertThat(key.next()).isTrue();
            assertThat(key.getString(1)).isEqualTo(
                    HexFormat.of().formatHex(HexFormat.of().parseHex(TenantSecurityIntegrationFixture.TENANT_CONTEXT_SIGNING_KEY)));
            assertThat(key.getString(2)).isEqualTo("ACTIVE");
        }
    }

    @Test
    void upgradesThePreviousReleaseAndIsIdempotent() throws Exception {
        String url = emptyDatabase("migration_job_upgrade");
        Flyway.configure()
                .dataSource(url, TenantSecurityIntegrationFixture.MIGRATION_ROLE,
                        TenantSecurityIntegrationFixture.MIGRATION_PASSWORD)
                .schemas("dokene").defaultSchema("dokene").createSchemas(true).cleanDisabled(true)
                .target(PREVIOUS_RELEASE_VERSION)
                .load().migrate();

        assertThat(runJob(url)).isZero();
        assertThat(Integer.parseInt(runtimeFlyway(url).info().current().getVersion().getVersion()))
                .isGreaterThan(Integer.parseInt(PREVIOUS_RELEASE_VERSION));
        assertThat(runJob(url)).as("re-running the job on a current schema").isZero();
        assertThat(runtimeFlyway(url).info().pending()).isEmpty();
        try (Connection migration = migrationConnection(url)) {
            assertThat(TenantSecurityIntegrationFixture.queryInt(migration, """
                    SELECT count(*) FROM pg_trigger
                    WHERE tgrelid = 'dokene.audit_events'::regclass AND tgname IN ('audit_events_immutable', 'audit_events_no_truncate')
                    """)).isEqualTo(2);
        }
    }

    @Test
    void runtimeRoleCanValidateButNeverApplyMigrations() throws Exception {
        String url = emptyDatabase("migration_job_runtime");
        Flyway.configure()
                .dataSource(url, TenantSecurityIntegrationFixture.MIGRATION_ROLE,
                        TenantSecurityIntegrationFixture.MIGRATION_PASSWORD)
                .schemas("dokene").defaultSchema("dokene").createSchemas(true).cleanDisabled(true)
                .target(PREVIOUS_RELEASE_VERSION)
                .load().migrate();

        // V33 predates the schema-history grant, so even validation is refused until the job runs. It is a SQL
        // failure, not a validation failure: RuntimeSchemaValidationConfiguration must wrap both with its hint.
        assertThatThrownBy(() -> runtimeFlyway(url).validate()).isInstanceOf(FlywaySqlException.class);
        assertThatThrownBy(() -> runtimeFlyway(url).migrate()).isInstanceOf(RuntimeException.class);
        assertThat(runJob(url)).isZero();
        runtimeFlyway(url).validate();

        try (Connection runtime = DriverManager.getConnection(url, TenantSecurityIntegrationFixture.RUNTIME_ROLE,
                TenantSecurityIntegrationFixture.RUNTIME_PASSWORD); Statement statement = runtime.createStatement()) {
            for (String sql : new String[] {
                    "CREATE TABLE dokene.runtime_probe (id INT)",
                    "DROP POLICY audit_events_select ON dokene.audit_events",
                    "ALTER TABLE dokene.audit_events DISABLE TRIGGER audit_events_immutable",
                    "SELECT count(*) FROM dokene.tenant_context_signing_keys",
                    "DELETE FROM dokene.flyway_schema_history"}) {
                assertThatThrownBy(() -> statement.execute(sql)).as(sql)
                        .isInstanceOfSatisfying(SQLException.class,
                                exception -> assertThat(exception.getSQLState()).isEqualTo("42501"));
            }
        }
    }

    @Test
    void runtimeRefusesToStartOnAPendingMigration() throws Exception {
        String url = emptyDatabase("migration_job_pending");
        assertThat(runJob(url)).isZero();
        Flyway withUnappliedMigration = Flyway.configure()
                .dataSource(url, TenantSecurityIntegrationFixture.RUNTIME_ROLE,
                        TenantSecurityIntegrationFixture.RUNTIME_PASSWORD)
                .schemas("dokene").defaultSchema("dokene")
                .locations("classpath:db/migration", "classpath:db/pending-migration-fixture")
                .load();
        assertThat(withUnappliedMigration.info().pending()).extracting(MigrationInfo::getDescription)
                .containsExactly("pending fixture");

        assertThatThrownBy(withUnappliedMigration::validate).isInstanceOf(FlywayValidateException.class);
    }

    @Test
    void runtimeConfigurationReferencesNoMigrationCredential() throws IOException {
        try (InputStream runtime = getClass().getResourceAsStream("/application.yml");
                InputStream job = getClass().getResourceAsStream("/application-migration.yml")) {
            String runtimeConfiguration = new String(runtime.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(runtimeConfiguration).doesNotContain("DOKENE_DB_MIGRATION");
            // Compose mounts the signing key under /run/secrets/dokene; only that directory becomes properties.
            assertThat(runtimeConfiguration).contains("import: optional:configtree:/run/secrets/dokene/");
            assertThat(new String(job.readAllBytes(), StandardCharsets.UTF_8)).contains("DOKENE_DB_MIGRATION_PASSWORD");
        }
    }

    private static int runJob(String url) {
        return SpringApplication.exit(DokeneMigrationApplication.application().run(jobArguments(url)));
    }

    private static String[] jobArguments(String url) {
        return new String[] {
                "--DOKENE_DB_URL=" + url,
                "--DOKENE_DB_MIGRATION_USERNAME=" + TenantSecurityIntegrationFixture.MIGRATION_ROLE,
                "--DOKENE_DB_MIGRATION_PASSWORD=" + TenantSecurityIntegrationFixture.MIGRATION_PASSWORD,
                "--DOKENE_TENANT_CONTEXT_SIGNING_KEY=" + TenantSecurityIntegrationFixture.TENANT_CONTEXT_SIGNING_KEY
        };
    }

    /** The runtime role's Flyway, configured as application.yml configures it for the backend. */
    private static Flyway runtimeFlyway(String url) {
        return Flyway.configure()
                .dataSource(url, TenantSecurityIntegrationFixture.RUNTIME_ROLE,
                        TenantSecurityIntegrationFixture.RUNTIME_PASSWORD)
                .schemas("dokene").defaultSchema("dokene").createSchemas(true).cleanDisabled(true)
                .load();
    }

    private static String emptyDatabase(String prefix) throws SQLException {
        return TenantSecurityIntegrationFixture.createEmptyDatabase(prefix + "_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static Connection migrationConnection(String url) throws SQLException {
        return DriverManager.getConnection(url, TenantSecurityIntegrationFixture.MIGRATION_ROLE,
                TenantSecurityIntegrationFixture.MIGRATION_PASSWORD);
    }
}
