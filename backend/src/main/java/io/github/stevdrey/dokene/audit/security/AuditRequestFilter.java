package io.github.stevdrey.dokene.audit.security;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs outside Spring Security so filter-level failures and MVC failures share one safe boundary. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditRequestFilter extends OncePerRequestFilter {
    public static final String CORRELATION_HEADER = "X-Request-Id";
    public static final String CORRELATION_MDC_KEY = "correlationId";
    private static final String CORS_HEADER_PREFIX = "Access-Control-";
    /** Vary plus every header Spring Security's header writers can emit; a new writer adds its header here. */
    private static final List<String> POLICY_HEADERS = List.of(
            HttpHeaders.VARY, "Content-Security-Policy", "Content-Security-Policy-Report-Only", "Permissions-Policy",
            "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Strict-Transport-Security",
            "X-XSS-Protection", HttpHeaders.CACHE_CONTROL, HttpHeaders.PRAGMA, HttpHeaders.EXPIRES,
            "Cross-Origin-Opener-Policy", "Cross-Origin-Embedder-Policy", "Cross-Origin-Resource-Policy");

    private final AuditExecutionContext execution;
    private final DatabaseUnavailability databaseUnavailability;

    public AuditRequestFilter(AuditExecutionContext execution, DatabaseUnavailability databaseUnavailability) {
        this.execution = execution;
        this.databaseUnavailability = databaseUnavailability;
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
            Optional<AuditPersistenceException> auditFailure = auditFailure(exception);
            if (auditFailure.isPresent() && !response.isCommitted()) {
                resetKeepingResponsePolicyHeaders(response, correlation);
                if (auditFailure.get().databaseUnavailable()) {
                    databaseUnavailability.apply(response);
                } else {
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                }
            } else if (databaseUnavailability.isCause(exception) && !response.isCommitted()) {
                // The one outage boundary (ADR 0029): filters such as tenant resolution reach the database before MVC,
                // and MVC failures no advice resolves leave the DispatcherServlet before the response is committed.
                databaseUnavailability.log(exception);
                resetKeepingResponsePolicyHeaders(response, correlation);
                databaseUnavailability.apply(response);
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

    /**
     * reset() drops every header, which is right for anything describing the failed response. Three kinds must
     * survive: the correlation id, which matters most on exactly these failure paths; the CORS headers, or a
     * cross-origin browser reports a network error instead of exposing the 503; and the security headers Spring
     * Security's header writer has already added, which every BFF response carries (ADR 0023, security invariant 22).
     */
    private void resetKeepingResponsePolicyHeaders(HttpServletResponse response, UUID correlation) {
        Map<String, List<String>> kept = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            if (isResponsePolicyHeader(name)) {
                kept.putIfAbsent(name, List.copyOf(response.getHeaders(name)));
            }
        }
        response.reset();
        kept.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setHeader(CORRELATION_HEADER, correlation.toString());
    }

    private static boolean isResponsePolicyHeader(String name) {
        return name.regionMatches(true, 0, CORS_HEADER_PREFIX, 0, CORS_HEADER_PREFIX.length())
                || POLICY_HEADERS.stream().anyMatch(name::equalsIgnoreCase);
    }

    private Optional<AuditPersistenceException> auditFailure(Throwable failure) {
        // Identity set: a cause chain can be cyclic, and this runs on every failure that reaches the filter.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof AuditPersistenceException audit) {
                return Optional.of(audit);
            }
        }
        return Optional.empty();
    }
}
