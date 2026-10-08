package io.github.stevdrey.dokene.customer.api;

import io.github.stevdrey.dokene.customer.application.CustomerConflictException;
import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.customer.application.CustomerValidationException;
import io.github.stevdrey.dokene.customer.application.CustomerValidationMessages;
import io.github.stevdrey.dokene.customer.domain.InvalidCustomerDisplayNameException;
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
        String message = ex.getMessage();
        if ("Invalid phone number".equalsIgnoreCase(message)) {
            message = CustomerValidationMessages.PHONE_INVALID;
        }
        return ResponseEntity.badRequest().body(new CustomerValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                message,
                ex.field()
        ));
    }

    @ExceptionHandler(InvalidCustomerDisplayNameException.class)
    ResponseEntity<CustomerValidationErrorResponse> invalidDisplayName(InvalidCustomerDisplayNameException ex) {
        String message = switch (ex.reason()) {
            case REQUIRED -> CustomerValidationMessages.DISPLAY_NAME_REQUIRED;
            case TOO_LONG -> CustomerValidationMessages.DISPLAY_NAME_TOO_LONG;
            case INVALID_CHARACTERS -> CustomerValidationMessages.DISPLAY_NAME_INVALID_CHARACTERS;
        };
        return ResponseEntity.badRequest().body(new CustomerValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(), message, "displayName"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    ResponseEntity<CustomerValidationErrorResponse> frameworkBindingError(Exception ex) {
        return ResponseEntity.badRequest().body(new CustomerValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                CustomerValidationMessages.REQUEST_INVALID,
                null
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<CustomerValidationErrorResponse> invalidInput(IllegalArgumentException ex) {
        String message = CustomerValidationMessages.INPUT_INVALID;
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
