package io.github.stevdrey.dokene.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;

@ExtendWith(OutputCaptureExtension.class)
class DatabaseUnavailabilityTest {
    private static final String SECRET_TEXT = "jdbc:postgresql://db.internal:5432/dokene user=dokene_runtime";

    private final DatabaseUnavailability unavailability = new DatabaseUnavailability(Duration.ofMillis(4_200));

    @Test
    void recognisesAPoolTimeoutDirectlyAndThroughTheJpaTransactionWrapping() {
        SQLTransientConnectionException poolTimeout = new SQLTransientConnectionException(SECRET_TEXT);
        assertThat(unavailability.isCause(poolTimeout)).isTrue();
        assertThat(unavailability.isCause(new CannotCreateTransactionException("begin failed",
                new JDBCConnectionException("Unable to acquire JDBC Connection", poolTimeout)))).isTrue();
        assertThat(unavailability.isCause(new CannotGetJdbcConnectionException("no connection"))).isTrue();
    }

    @Test
    void recognisesDriverConnectionFailuresBySqlStateClass08() {
        assertThat(unavailability.isCause(new RuntimeException(new SQLException(SECRET_TEXT, "08001")))).isTrue();
        assertThat(unavailability.isCause(new SQLException("syntax", "42601"))).isFalse();
    }

    @Test
    void recognisesPostgresqlShuttingDownCrashingOrStartingButNotACancelledQuery() {
        assertThat(unavailability.isCause(new SQLException(SECRET_TEXT, "57P01"))).isTrue();
        assertThat(unavailability.isCause(new SQLException(SECRET_TEXT, "57P02"))).isTrue();
        assertThat(unavailability.isCause(new SQLException(SECRET_TEXT, "57P03"))).isTrue();
        assertThat(unavailability.isCause(new SQLException("canceling statement", "57014"))).isFalse();
        assertThat(unavailability.isCause(new SQLException("no state"))).isFalse();
    }

    @Test
    void readsJdbcTemplateTranslationsByTheSqlStateTheyWrap() {
        // What JdbcTemplate throws for a socket timeout in flight, a server shutdown, and a full disk.
        assertThat(unavailability.isCause(translate("08006"))).isTrue();
        assertThat(unavailability.isCause(translate("57P01"))).isTrue();
        assertThat(unavailability.isCause(translate("53100"))).isFalse();
    }

    @Test
    void leavesOtherFailuresAlone() {
        assertThat(unavailability.isCause(new DataIntegrityViolationException("duplicate"))).isFalse();
        assertThat(unavailability.isCause(new CannotCreateTransactionException("begin failed",
                new IllegalStateException("unrelated")))).isFalse();
        assertThat(unavailability.isCause(null)).isFalse();
    }

    @Test
    void stopsOnACyclicCauseChain() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertThat(unavailability.isCause(first)).isFalse();
    }

    @Test
    void answersAnEmpty503WithRetryAfterRoundedUp() {
        var response = unavailability.response();
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(response.getBody()).isNull();

        MockHttpServletResponse servlet = new MockHttpServletResponse();
        unavailability.apply(servlet);
        assertThat(servlet.getStatus()).isEqualTo(503);
        assertThat(servlet.getHeader("Retry-After")).isEqualTo("5");
    }

    @Test
    void logsOnlyTheInnermostConnectionFailureClass(CapturedOutput output) {
        unavailability.log(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                new JDBCConnectionException("Unable to acquire JDBC Connection",
                        new SQLTransientConnectionException(SECRET_TEXT))));
        assertThat(output).contains("exceptionClass=java.sql.SQLTransientConnectionException")
                .doesNotContain(SECRET_TEXT).doesNotContain("db.internal");
    }

    private static RuntimeException translate(String sqlState) {
        RuntimeException translated = new SQLStateSQLExceptionTranslator()
                .translate("query", "SELECT 1", new SQLException(SECRET_TEXT, sqlState));
        assertThat(translated).isInstanceOf(DataAccessResourceFailureException.class);
        return translated;
    }
}
