package io.github.stevdrey.dokene.tenant.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

class TenantPersistenceConfigurationTest {

    @Test
    void keepsAValidationTimeoutThatFitsInsideThePoolWait() {
        assertThat(validationTimeoutFor(3_000, 1_000)).isEqualTo(1_000);
    }

    @Test
    void capsTheValidationTimeoutAtHalfThePoolWait() {
        assertThat(validationTimeoutFor(1_000, 1_000)).isEqualTo(500);
        assertThat(validationTimeoutFor(600, 5_000)).isEqualTo(300);
    }

    @Test
    void neverGoesBelowHikarisFloor() {
        assertThat(validationTimeoutFor(250, 1_000)).isEqualTo(TenantPersistenceConfiguration.HIKARI_TIMEOUT_FLOOR_MS);
    }

    private static long validationTimeoutFor(long connectionTimeout, long validationTimeout) {
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setConnectionTimeout(connectionTimeout);
            pool.setValidationTimeout(validationTimeout);
            TenantPersistenceConfiguration.boundValidationTimeout(pool);
            return pool.getValidationTimeout();
        }
    }
}
