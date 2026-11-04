// ABOUTME: One-shot schema migration job (issue 308): applies the Flyway migrations as dokene_migration and exits.
// ABOUTME: Loads no web, OIDC or runtime datasource beans, so only this job ever holds the migration credentials.
package io.github.stevdrey.dokene.migration;

import io.github.stevdrey.dokene.tenant.persistence.jpa.TenantContextSigningKeyCallback;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Entry point of the migration job. Deliberately not a {@code @Configuration}: the runtime application's component
 * scan must never pick it up. Spring processes it as a lite configuration source, which is enough for the imports.
 *
 * <p>{@code FlywayAutoConfiguration} needs no {@code DataSource} bean because {@code spring.flyway.url} is set; the
 * {@value #PROFILE} profile ({@code application-migration.yml}) points {@code spring.flyway.user/password} at the
 * migration role. A failed migration propagates out of {@code run} and the JVM exits non-zero.
 */
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(TenantContextSigningKeyCallback.class)
public class DokeneMigrationApplication {
    static final String PROFILE = "migration";

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(application().run(args)));
    }

    public static SpringApplication application() {
        SpringApplication application = new SpringApplication(DokeneMigrationApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles(PROFILE);
        return application;
    }
}
