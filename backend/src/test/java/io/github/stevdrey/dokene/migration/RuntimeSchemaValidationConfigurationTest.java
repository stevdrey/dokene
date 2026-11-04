// ABOUTME: Pins the runtime Flyway strategy (issue 308): validate by default with an actionable failure, and migrate
// ABOUTME: only when the test-only migrate-on-startup switch is set.
package io.github.stevdrey.dokene.migration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;

class RuntimeSchemaValidationConfigurationTest {
    private final RuntimeSchemaValidationConfiguration configuration = new RuntimeSchemaValidationConfiguration();

    @Test
    void validatesWithoutMigratingByDefault() {
        Flyway flyway = mock(Flyway.class);

        configuration.runtimeFlywayMigrationStrategy(false).migrate(flyway);

        verify(flyway).validate();
        verify(flyway, never()).migrate();
    }

    @Test
    void pointsAtTheMigrationJobWhenTheSchemaDoesNotMatch() {
        Flyway flyway = mock(Flyway.class);
        doThrow(FlywayValidateException.class).when(flyway).validate();

        assertThatThrownBy(() -> configuration.runtimeFlywayMigrationStrategy(false).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Run the migration job")
                .hasCauseInstanceOf(FlywayValidateException.class);
        verify(flyway, never()).migrate();
    }

    @Test
    void migratesOnlyWhenExplicitlyEnabled() {
        Flyway flyway = mock(Flyway.class);

        configuration.runtimeFlywayMigrationStrategy(true).migrate(flyway);

        verify(flyway).migrate();
        verify(flyway, never()).validate();
    }
}
