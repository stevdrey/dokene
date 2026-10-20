package io.github.stevdrey.dokene.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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
        assertThat(unavailability.isCause(new SQLException("no state"))).isFalse();
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
    void mvcOutageIsAnEmptyRetryable503AndLogsOnlyTheFailureClass(CapturedOutput output) throws Exception {
        mvc().perform(get("/outage"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(content().string(""));
        assertThat(output).contains("exceptionClass=java.sql.SQLTransientConnectionException")
                .doesNotContain(SECRET_TEXT).doesNotContain("db.internal");
    }

    @Test
    void transactionThatCannotStartForAnotherReasonIsAnEmpty500(CapturedOutput output) throws Exception {
        mvc().perform(get("/defect"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(content().string(""));
        assertThat(output).doesNotContain(SECRET_TEXT);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new DatabaseUnavailableExceptionHandler(unavailability)).build();
    }

    @RestController
    static class FailingController {
        @GetMapping("/outage")
        public void outage() {
            throw new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                    new JDBCConnectionException("Unable to acquire JDBC Connection",
                            new SQLTransientConnectionException(SECRET_TEXT)));
        }

        @GetMapping("/defect")
        public void defect() {
            throw new CannotCreateTransactionException(SECRET_TEXT, new IllegalStateException(SECRET_TEXT));
        }
    }
}
