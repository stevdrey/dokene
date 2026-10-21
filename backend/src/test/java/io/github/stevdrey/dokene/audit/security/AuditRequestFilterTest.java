package io.github.stevdrey.dokene.audit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import jakarta.servlet.ServletException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@ExtendWith(OutputCaptureExtension.class)
class AuditRequestFilterTest {
    private static final String SECRET_TEXT = "jdbc:postgresql://db.internal:5432/dokene user=dokene_runtime";

    private final AuditExecutionContext context = new AuditExecutionContext();
    private final DatabaseUnavailability unavailability = new DatabaseUnavailability(Duration.ofSeconds(5));
    private final AuditRequestFilter filter = new AuditRequestFilter(context, unavailability);

    @Test
    void ignoresClientCorrelationAndClearsRequestScope() throws Exception {
        UUID supplied = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", supplied.toString());
        AtomicReference<UUID> first = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> first.set(context.requireCurrent()));
        assertThat(first.get()).isNotNull().isNotEqualTo(supplied);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> assertThat(context.requireCurrent()).isNotEqualTo(first.get()));
        assertThat(context.current()).isEmpty();
    }

    @Test
    void exposesServerCorrelationInMdcAndResponseHeaderOnlyForTheRequest() throws Exception {
        UUID supplied = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", supplied.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();
        AtomicReference<UUID> correlation = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            mdcDuringRequest.set(org.slf4j.MDC.get("correlationId"));
            correlation.set(context.requireCurrent());
        });

        assertThat(mdcDuringRequest.get()).isEqualTo(correlation.get().toString());
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(correlation.get().toString())
                .isNotEqualTo(supplied.toString());
        assertThat(org.slf4j.MDC.get("correlationId")).isNull();
    }

    @Test
    void clearsMdcEvenWhenTheRequestFails() {
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> {
                    throw new IllegalStateException("unrelated");
                })).isInstanceOf(IllegalStateException.class);
        assertThat(org.slf4j.MDC.get("correlationId")).isNull();
    }

    @Test
    void convertsSecurityFilterFailureToGeneric503() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {
            throw new ServletException("untrusted exception details", new AuditPersistenceException());
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(context.current()).isEmpty();
    }

    @Test
    void auditFailureResponseStillCarriesTheServerGeneratedCorrelationHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<UUID> correlation = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {
            correlation.set(context.requireCurrent());
            ((jakarta.servlet.http.HttpServletResponse) res).setHeader("X-Other", "dropped-by-reset");
            throw new ServletException("untrusted exception details", new AuditPersistenceException());
        });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(correlation.get().toString());
        assertThat(response.getHeader("X-Other")).isNull();
        assertThat(org.slf4j.MDC.get("correlationId")).isNull();
    }

    @Test
    void mvcFailureHasTheSameGeneric503Response() throws Exception {
        MockMvcBuilders.standaloneSetup(new FailingController()).setControllerAdvice(new AuditExceptionHandler(unavailability))
                .addFilters(filter).build().perform(get("/failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
    }

    @Test
    void filterLevelDatabaseOutageIsAnEmptyRetryable503WithTheCorrelationHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<UUID> correlation = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {
            correlation.set(context.requireCurrent());
            var servlet = (jakarta.servlet.http.HttpServletResponse) res;
            servlet.setHeader("X-Other", "dropped-by-reset");
            // What Spring Security's CORS filter wrote for an allowed origin before tenant resolution failed.
            servlet.setHeader("Access-Control-Allow-Origin", "http://localhost:5173");
            servlet.setHeader("Access-Control-Allow-Credentials", "true");
            servlet.addHeader("Vary", "Origin");
            servlet.addHeader("Vary", "Access-Control-Request-Method");
            // What Spring Security's header writer added in its finally block before the failure left the chain.
            servlet.setHeader("Content-Security-Policy", "default-src 'none'");
            servlet.setHeader("X-Content-Type-Options", "nosniff");
            servlet.setHeader("Cache-Control", "no-store");
            servlet.setHeader("ETag", "\"3\"");
            throw new CannotCreateTransactionException("membership lookup",
                    new SQLTransientConnectionException("HikariPool-1 - Connection is not available"));
        });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(correlation.get().toString());
        assertThat(response.getHeader("X-Other")).isNull();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(response.getHeaders("Vary")).containsExactly("Origin", "Access-Control-Request-Method");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("ETag")).isNull();
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(context.current()).isEmpty();
    }

    @Test
    void auditFailureKeepsTheCorsHeadersSoCrossOriginClientsSeeThe503() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse) res)
                    .setHeader("Access-Control-Allow-Origin", "http://localhost:5173");
            throw new ServletException("untrusted exception details", new AuditPersistenceException());
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
    }

    @Test
    void doesNotHideUnrelatedFailures() {
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {
            throw new IllegalStateException("unrelated");
        })).isInstanceOf(IllegalStateException.class).hasMessage("unrelated");
        assertThat(context.current()).isEmpty();
    }

    @Test
    void anMvcOutageNoAdviceResolvesIsAnEmptyRetryable503AtTheFilter(CapturedOutput output) throws Exception {
        MockMvc mvc = filteredMvcWithoutDatabaseAdvice();
        for (String path : List.of("/jpa-begin", "/jdbc-socket-timeout", "/jdbc-admin-shutdown")) {
            mvc.perform(get(path))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(header().exists("X-Request-Id"))
                    .andExpect(content().string(""));
        }
        assertThat(output).contains("Database unavailable at the HTTP boundary").doesNotContain(SECRET_TEXT);
    }

    @Test
    void anMvcDatabaseFailureThatIsNotAnOutageKeepsItsDefaultHandling() {
        // A full disk is not something the client can wait out: it must not be dressed up as a retryable 503.
        assertThatThrownBy(() -> filteredMvcWithoutDatabaseAdvice().perform(get("/jdbc-disk-full")))
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().satisfies(root -> assertThat(((SQLException) root).getSQLState()).isEqualTo("53100"));
    }

    @Test
    void anAuditWriteLostToTheOutageCarriesRetryAfterAtBothBoundaries() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {
            throw new ServletException("untrusted exception details", new AuditPersistenceException(true));
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");

        MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new AuditExceptionHandler(unavailability))
                .addFilters(filter).build().perform(get("/audit-outage"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(content().string(""));
    }

    @Test
    void anAuditFailureForAnotherReasonHasNoRetryAfter() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {
            throw new ServletException("untrusted exception details", new AuditPersistenceException());
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isNull();

        MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new AuditExceptionHandler(unavailability))
                .addFilters(filter).build().perform(get("/failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void aCyclicCauseChainIsRethrownInsteadOfLoopingForever() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThatThrownBy(() -> filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {
                    throw first;
                })).isSameAs(first));
    }

    private MockMvc filteredMvcWithoutDatabaseAdvice() {
        return MockMvcBuilders.standaloneSetup(new FailingController()).addFilters(filter).build();
    }

    @RestController
    static class FailingController {
        @GetMapping("/failure")
        public void fail() { throw new AuditPersistenceException(); }

        @GetMapping("/audit-outage")
        public void auditOutage() { throw new AuditPersistenceException(true); }

        @GetMapping("/jpa-begin")
        public void jpaBegin() {
            throw new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                    new JDBCConnectionException("Unable to acquire JDBC Connection",
                            new SQLTransientConnectionException(SECRET_TEXT)));
        }

        /** What JdbcTemplate throws when the driver socket timeout fires on a statement in flight. */
        @GetMapping("/jdbc-socket-timeout")
        public void socketTimeout() { throw translate("08006"); }

        /** What JdbcTemplate throws when PostgreSQL stops while a statement is running (admin_shutdown). */
        @GetMapping("/jdbc-admin-shutdown")
        public void adminShutdown() { throw translate("57P01"); }

        @GetMapping("/jdbc-disk-full")
        public void diskFull() { throw translate("53100"); }

        private static RuntimeException translate(String sqlState) {
            return new SQLStateSQLExceptionTranslator()
                    .translate("query", "SELECT 1", new SQLException(SECRET_TEXT, sqlState));
        }
    }
}
