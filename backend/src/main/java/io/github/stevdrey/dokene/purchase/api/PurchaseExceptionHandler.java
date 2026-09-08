package io.github.stevdrey.dokene.purchase.api;

import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.purchase.application.PurchaseConflictException;
import io.github.stevdrey.dokene.purchase.application.PurchaseNotFoundException;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = PurchaseController.class)
public class PurchaseExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }

    @ExceptionHandler({PurchaseNotFoundException.class, CustomerNotFoundException.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.notFound().build(); }

    @ExceptionHandler({PurchaseConflictException.class, IllegalStateException.class})
    ResponseEntity<Void> conflict() { return ResponseEntity.status(HttpStatus.CONFLICT).build(); }

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<Void> forbidden() { return ResponseEntity.status(HttpStatus.FORBIDDEN).build(); }
}
