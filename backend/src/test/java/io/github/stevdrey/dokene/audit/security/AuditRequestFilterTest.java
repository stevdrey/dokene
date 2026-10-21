package io.github.stevdrey.dokene.audit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import jakarta.servlet.ServletException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class AuditRequestFilterTest {
    private final AuditExecutionContext context = new AuditExecutionContext();
    private final AuditRequestFilter filter =
            new AuditRequestFilter(context, new DatabaseUnavailability(Duration.ofSeconds(5)));

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
        MockMvcBuilders.standaloneSetup(new FailingController()).setControllerAdvice(new AuditExceptionHandler())
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

    @RestController
    static class FailingController {
        @GetMapping("/failure")
        public void fail() { throw new AuditPersistenceException(); }
    }
}
