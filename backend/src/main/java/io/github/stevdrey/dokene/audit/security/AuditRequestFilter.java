package io.github.stevdrey.dokene.audit.security;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs outside Spring Security so filter-level failures and MVC failures share one safe boundary. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditRequestFilter extends OncePerRequestFilter {
    public static final String CORRELATION_HEADER = "X-Request-Id";
    public static final String CORRELATION_MDC_KEY = "correlationId";

    private final AuditExecutionContext execution;

    public AuditRequestFilter(AuditExecutionContext execution) {
        this.execution = execution;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID correlation = UUID.randomUUID();
        // Server-generated only: any inbound X-Request-Id is ignored (ADR 0006). Echoed for support diagnostics.
        response.setHeader(CORRELATION_HEADER, correlation.toString());
        MDC.put(CORRELATION_MDC_KEY, correlation.toString());
        try {
            execution.callWithCorrelation(correlation, () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (Exception exception) {
            if (isAuditFailure(exception) && !response.isCommitted()) {
                response.reset();
                // reset() drops every header; the correlation id matters most on exactly this failure path.
                response.setHeader(CORRELATION_HEADER, correlation.toString());
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            } else if (exception instanceof IOException io) {
                throw io;
            } else if (exception instanceof ServletException servlet) {
                throw servlet;
            } else if (exception instanceof RuntimeException runtime) {
                throw runtime;
            } else {
                throw new ServletException(exception);
            }
        } finally {
            MDC.remove(CORRELATION_MDC_KEY);
        }
    }

    private boolean isAuditFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof AuditPersistenceException) {
                return true;
            }
        }
        return false;
    }
}
