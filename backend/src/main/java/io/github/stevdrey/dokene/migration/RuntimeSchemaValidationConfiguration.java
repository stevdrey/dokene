// ABOUTME: Runtime Flyway strategy (issue 308): the backend only validates the schema the migration job applied.
// ABOUTME: Startup fails on a pending, failed or changed migration instead of migrating with credentials it lacks.
package io.github.stevdrey.dokene.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RuntimeSchemaValidationConfiguration {

    /**
     * {@code dokene.database.migrate-on-startup} exists for integration tests that boot the whole application against
     * an empty database; they also supply the migration credentials, which are the actual control. Deployments leave
     * it unset and run {@link DokeneMigrationApplication} first.
     */
    @Bean
    FlywayMigrationStrategy runtimeFlywayMigrationStrategy(
            @Value("${dokene.database.migrate-on-startup:false}") boolean migrateOnStartup
    ) {
        return migrateOnStartup ? Flyway::migrate : RuntimeSchemaValidationConfiguration::validate;
    }

    /**
     * Any Flyway failure gets the same hint: a pending migration fails validation, while a schema older than V34 refuses
     * the runtime role read access to the history table before validation can even start.
     */
    private static void validate(Flyway flyway) {
        try {
            flyway.validate();
        } catch (FlywayException exception) {
            throw new IllegalStateException(
                    "The database schema does not match this release. Run the migration job (Compose service "
                            + "'migrate') before starting the backend.",
                    exception
            );
        }
    }
}
