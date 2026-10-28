package io.github.stevdrey.dokene.audit.security;

import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Ordered first: Spring uses the first advice with a matching method, so an audit failure always gets this answer,
 * even on routes whose own advice has a last-resort {@code Exception} handler (issue 293).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class AuditExceptionHandler {
    private final DatabaseUnavailability databaseUnavailability;

    public AuditExceptionHandler(DatabaseUnavailability databaseUnavailability) {
        this.databaseUnavailability = databaseUnavailability;
    }

    /** An audit write lost to a database outage is that outage, so it carries Retry-After too (ADR 0029). */
    @ExceptionHandler(AuditPersistenceException.class)
    public ResponseEntity<Void> auditUnavailable(AuditPersistenceException failure) {
        return failure.databaseUnavailable()
                ? databaseUnavailability.response()
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
