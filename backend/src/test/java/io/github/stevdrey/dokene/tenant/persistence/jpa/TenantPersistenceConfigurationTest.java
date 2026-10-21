package io.github.stevdrey.dokene.tenant.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TenantPersistenceConfigurationTest {
    private static final Duration SOCKET = Duration.ofSeconds(30);
    private static final Duration STATEMENT = Duration.ofSeconds(25);

    @Test
    void keepsAValidationTimeoutThatFitsInsideThePoolWait() {
        assertThat(boundedPool(3_000, 1_000, SOCKET, STATEMENT).getValidationTimeout()).isEqualTo(1_000);
    }

    @Test
    void capsTheValidationTimeoutAtHalfThePoolWait() {
        assertThat(boundedPool(1_000, 1_000, SOCKET, STATEMENT).getValidationTimeout()).isEqualTo(500);
        assertThat(boundedPool(600, 5_000, SOCKET, STATEMENT).getValidationTimeout()).isEqualTo(300);
        assertThat(boundedPool(500, 1_000, SOCKET, STATEMENT).getValidationTimeout())
                .isEqualTo(TenantPersistenceConfiguration.HIKARI_TIMEOUT_FLOOR_MS);
    }

    @Test
    void refusesAPoolWaitWithNoRoomForTheLivenessCheck() {
        assertThatThrownBy(() -> boundedPool(499, 1_000, SOCKET, STATEMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DOKENE_DB_CONNECTION_TIMEOUT_MS");
    }

    @Test
    void refusesAStatementTimeoutThatWouldNotFireBeforeTheSocketTimeout() {
        assertThatThrownBy(() -> boundedPool(3_000, 1_000, SOCKET, SOCKET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DOKENE_DB_STATEMENT_TIMEOUT");
        assertThatThrownBy(() -> boundedPool(3_000, 1_000, SOCKET, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setsTheDriverSocketCeilingAndTheServerStatementTimeout() {
        HikariDataSource pool = boundedPool(3_000, 1_000, Duration.ofMillis(29_500), STATEMENT);
        assertThat(pool.getDataSourceProperties())
                .containsEntry("socketTimeout", "30")
                .containsEntry("options", "-c statement_timeout=25000");
    }

    private static HikariDataSource boundedPool(long connectionTimeout, long validationTimeout, Duration socketTimeout,
            Duration statementTimeout) {
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setConnectionTimeout(connectionTimeout);
            pool.setValidationTimeout(validationTimeout);
            TenantPersistenceConfiguration.applyOutageBounds(pool, socketTimeout, statementTimeout);
            return pool;
        }
    }
}
