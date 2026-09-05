package io.github.stevdrey.dokene.audit.security;

import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuditExceptionHandler {
    @ExceptionHandler(AuditPersistenceException.class)
    public ResponseEntity<Void> auditUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
