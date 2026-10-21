package io.github.stevdrey.dokene.tenant.persistence.jpa;

import com.zaxxer.hikari.HikariDataSource;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
class TenantPersistenceConfiguration {
    /** Hikari's own floor for every timeout. */
    static final long HIKARI_TIMEOUT_FLOOR_MS = 250;
    /** Room for a liveness check at Hikari's floor plus another borrow inside the same pool wait. */
    static final long MIN_POOL_WAIT_MS = 2 * HIKARI_TIMEOUT_FLOOR_MS;

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
            DatabaseContextSigner databaseContextSigner,
            @Value("${dokene.datasource.socket-timeout:30s}") Duration socketTimeout,
            @Value("${dokene.datasource.statement-timeout:25s}") Duration statementTimeout
    ) {
        // Bound properties are applied by now and the pool starts lazily, so the timeouts can still be adjusted.
        applyOutageBounds(hikariDataSource, socketTimeout, statementTimeout);
        return new TenantAwareDataSource(hikariDataSource, tenantContextProvider, databaseContextSigner);
    }

    /**
     * Bounds every way a request can wait on the runtime database (ADR 0029 §1) and refuses settings that cannot
     * keep the bound.
     * <ul>
     *   <li>Hikari does not keep {@code validationTimeout} below {@code connectionTimeout}. A borrower that meets a
     *   stale idle connection after a blackholed outage spends the validation timeout on it before trying another,
     *   so it is capped at half the pool wait. A pool wait below {@value #MIN_POOL_WAIT_MS} ms leaves no room for
     *   that at Hikari's floor.</li>
     *   <li>pgJDBC's socket timeout is the ceiling for a statement in flight when the network goes silent; on expiry
     *   the driver reports a lost connection (08006), an outage. The server-side {@code statement_timeout} must fire
     *   first, so a statement or lock wait that is merely long is cancelled as {@code query_canceled} (57014), is not
     *   reported as an outage, and keeps its connection.</li>
     * </ul>
     */
    static void applyOutageBounds(HikariDataSource pool, Duration socketTimeout, Duration statementTimeout) {
        if (pool.getConnectionTimeout() < MIN_POOL_WAIT_MS) {
            throw new IllegalStateException("spring.datasource.hikari.connection-timeout"
                    + " (DOKENE_DB_CONNECTION_TIMEOUT_MS) must be at least " + MIN_POOL_WAIT_MS + " ms");
        }
        if (statementTimeout.isNegative() || statementTimeout.isZero()
                || statementTimeout.compareTo(socketTimeout) >= 0) {
            throw new IllegalStateException("dokene.datasource.statement-timeout (DOKENE_DB_STATEMENT_TIMEOUT) must be"
                    + " positive and shorter than dokene.datasource.socket-timeout (DOKENE_DB_SOCKET_TIMEOUT)");
        }
        long validationCeiling = Math.max(HIKARI_TIMEOUT_FLOOR_MS, pool.getConnectionTimeout() / 2);
        if (pool.getValidationTimeout() > validationCeiling) {
            pool.setValidationTimeout(validationCeiling);
        }
        // pgJDBC takes whole seconds; round up so the socket ceiling never undercuts the statement timeout.
        long socketSeconds = socketTimeout.plusMillis(999).toSeconds();
        pool.addDataSourceProperty("socketTimeout", String.valueOf(socketSeconds));
        pool.addDataSourceProperty("options", "-c statement_timeout=" + statementTimeout.toMillis());
    }
}
