package io.github.stevdrey.dokene.audit.security;

import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
