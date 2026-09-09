package io.github.stevdrey.dokene.followup.api;

import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.followup.application.FollowUpConflictException;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import java.time.DateTimeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = FollowUpController.class)
public class FollowUpExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, DateTimeException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    ResponseEntity<Void> invalidInput() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(FollowUpConflictException.class)
    ResponseEntity<Void> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
