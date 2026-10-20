// ABOUTME: MVC boundary for a database outage: an empty 503 with Retry-After instead of a generic 500 (issue 246).
// ABOUTME: Filter-level outages (tenant resolution runs before MVC) are mapped by AuditRequestFilter instead.
package io.github.stevdrey.dokene.database;

import java.sql.SQLTransientConnectionException;
import org.hibernate.exception.JDBCConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Runs before the module-scoped advices, so none of them can turn an outage into a module-specific answer. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseUnavailableExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(DatabaseUnavailableExceptionHandler.class);

    private final DatabaseUnavailability databaseUnavailability;

    public DatabaseUnavailableExceptionHandler(DatabaseUnavailability databaseUnavailability) {
        this.databaseUnavailability = databaseUnavailability;
    }

    /** Spring also matches these types as causes, so wrapped failures (JPA, transaction begin) land here too. */
    @ExceptionHandler({SQLTransientConnectionException.class, CannotGetJdbcConnectionException.class,
            JDBCConnectionException.class, CannotCreateTransactionException.class})
    ResponseEntity<Void> databaseFailure(Exception ex) {
        if (databaseUnavailability.isCause(ex)) {
            databaseUnavailability.log(ex);
            return databaseUnavailability.response();
        }
        // A transaction that could not start for another reason is a defect, not an outage. Class and top frame
        // locate it; the message and cause may carry SQL or connection details.
        StackTraceElement[] frames = ex.getStackTrace();
        log.error("Transaction could not start: exceptionClass={}, at={}",
                ex.getClass().getName(), frames.length > 0 ? frames[0] : "unknown");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
