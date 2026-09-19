package io.github.stevdrey.dokene.customer.api;

import io.github.stevdrey.dokene.customer.application.CustomerConflictException;
import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.customer.application.CustomerValidationException;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {CustomerController.class, ContactPolicyController.class})
public class CustomerExceptionHandler {
    public record CustomerValidationErrorResponse(int status, String message, String field) { }

    @ExceptionHandler(CustomerValidationException.class)
    ResponseEntity<CustomerValidationErrorResponse> validationError(CustomerValidationException ex) {
        return ResponseEntity.badRequest().body(new CustomerValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                ex.field()
        ));
    }

    @ExceptionHandler({IllegalArgumentException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<CustomerValidationErrorResponse> invalidInput(Exception ex) {
        String message = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "Invalid input";
        return ResponseEntity.badRequest().body(new CustomerValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                message,
                null
        ));
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler({CustomerConflictException.class, IllegalStateException.class})
    ResponseEntity<Void> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
