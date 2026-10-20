// ABOUTME: Recognises a database outage (no connection could be obtained or kept) anywhere in a failure's cause chain
// ABOUTME: and shapes the one HTTP answer for it: an empty 503 with Retry-After (issue 246, ADR 0029).
package io.github.stevdrey.dokene.database;

import io.github.stevdrey.dokene.ratelimit.RetryAfter;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hibernate.exception.JDBCConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

@Component
public class DatabaseUnavailability {
    private static final Logger log = LoggerFactory.getLogger(DatabaseUnavailability.class);
    /** SQLState class 08 is "connection exception" in SQL:2016 and in the PostgreSQL driver. */
    private static final String CONNECTION_EXCEPTION_CLASS = "08";

    private final long retryAfterSeconds;

    public DatabaseUnavailability(
            @Value("${dokene.datasource.unavailable-retry-after:5s}") Duration retryAfter) {
        this.retryAfterSeconds = RetryAfter.seconds(retryAfter);
    }

    /** True when the failure, or anything it wraps, means no database connection was available. */
    public boolean isCause(Throwable failure) {
        return outage(failure).isPresent();
    }

    /**
     * Logs the outage with the class of the connection-level failure only: driver and pool messages can carry host
     * names, user names and statements, so neither the message nor the cause is ever logged. The MDC has the
     * correlation id.
     */
    public void log(Throwable failure) {
        log.warn("Database unavailable at the HTTP boundary: exceptionClass={}",
                deepestOutage(failure).map(cause -> cause.getClass().getName()).orElse("unknown"));
    }

    public ResponseEntity<Void> response() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .build();
    }

    /** For filter-level failures, after the caller has reset the response. */
    public void apply(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
    }

    private Optional<Throwable> outage(Throwable failure) {
        return causes(failure).stream().filter(this::isConnectionFailure).findFirst();
    }

    /** The innermost connection-level failure (pool timeout or driver refusal) says most about the outage. */
    private Optional<Throwable> deepestOutage(Throwable failure) {
        return causes(failure).reversed().stream().filter(this::isConnectionFailure).findFirst();
    }

    private List<Throwable> causes(Throwable failure) {
        // Identity set: a cause chain can be cyclic, and equals() on exceptions is not to be trusted.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    private boolean isConnectionFailure(Throwable failure) {
        return switch (failure) {
            case SQLTransientConnectionException ignored -> true;
            case CannotGetJdbcConnectionException ignored -> true;
            case JDBCConnectionException ignored -> true;
            case SQLException sql -> sql.getSQLState() != null
                    && sql.getSQLState().startsWith(CONNECTION_EXCEPTION_CLASS);
            default -> false;
        };
    }
}
