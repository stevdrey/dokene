package io.github.stevdrey.dokene.tenant.persistence.jpa;

import com.zaxxer.hikari.HikariDataSource;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
class TenantPersistenceConfiguration {
    /** Hikari's own floor for every timeout. */
    static final long HIKARI_TIMEOUT_FLOOR_MS = 250;

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    HikariDataSource hikariDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    TenantAwareDataSource dataSource(
            HikariDataSource hikariDataSource,
            TenantContextProvider tenantContextProvider,
            DatabaseContextSigner databaseContextSigner
    ) {
        // Bound properties are applied by now and the pool starts lazily, so the timeouts can still be adjusted.
        boundValidationTimeout(hikariDataSource);
        return new TenantAwareDataSource(hikariDataSource, tenantContextProvider, databaseContextSigner);
    }

    /**
     * Hikari does not keep {@code validationTimeout} below {@code connectionTimeout}. A borrower that meets a stale
     * idle connection after a blackholed outage spends the validation timeout on it before trying another, so a
     * longer liveness check would outlast the advertised pool wait (ADR 0029). Cap it at half the wait.
     */
    static void boundValidationTimeout(HikariDataSource pool) {
        long ceiling = Math.max(HIKARI_TIMEOUT_FLOOR_MS, pool.getConnectionTimeout() / 2);
        if (pool.getValidationTimeout() > ceiling) {
            pool.setValidationTimeout(ceiling);
        }
    }
}
