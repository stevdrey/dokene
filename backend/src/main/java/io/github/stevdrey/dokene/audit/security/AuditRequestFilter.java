package io.github.stevdrey.dokene.audit.security;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs outside Spring Security so filter-level failures and MVC failures share one safe boundary. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditRequestFilter extends OncePerRequestFilter {
    private final AuditExecutionContext execution;

    public AuditRequestFilter(AuditExecutionContext execution) {
        this.execution = execution;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            execution.callWithCorrelation(UUID.randomUUID(), () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (Exception exception) {
            if (isAuditFailure(exception) && !response.isCommitted()) {
                response.reset();
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
