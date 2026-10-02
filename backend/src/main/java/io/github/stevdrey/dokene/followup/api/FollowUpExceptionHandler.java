package io.github.stevdrey.dokene.followup.api;

import io.github.stevdrey.dokene.ai.application.AiProviderException;
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

import io.github.stevdrey.dokene.followup.application.RecommendationRateLimitExceededException;
import org.springframework.http.HttpHeaders;

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

    /** Provider text and framework messages never cross the HTTP boundary; the body is intentionally empty. */
    @ExceptionHandler(AiProviderException.class)
    ResponseEntity<Void> aiProviderFailure() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Void> unexpectedState() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @ExceptionHandler(RecommendationRateLimitExceededException.class)
    ResponseEntity<Void> rateLimited(RecommendationRateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .build();
    }
}
