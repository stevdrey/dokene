package io.github.stevdrey.dokene.integration.api;

import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.http.UnexpectedFailures;
import io.github.stevdrey.dokene.integration.api.IntegrationApiContract.InvalidRequestException;
import io.github.stevdrey.dokene.integration.application.IntegrationRateLimitedException;
import io.github.stevdrey.dokene.integration.application.IntegrationRefusedException;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessagingProblem;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Closed-code problem+json only: no detail, ids or tenant hints (ADR 0023 §4.4). */
@RestControllerAdvice(assignableTypes = WhatsAppIntegrationController.class)
public class IntegrationExceptionHandler {
    private final DatabaseUnavailability databaseUnavailability;

    public IntegrationExceptionHandler(DatabaseUnavailability databaseUnavailability) {
        this.databaseUnavailability = databaseUnavailability;
    }

    @ExceptionHandler(IntegrationRefusedException.class)
    ResponseEntity<MessagingProblem> refused(IntegrationRefusedException refusal) {
        return problem(refusal.code());
    }

    /** The closed problem plus {@code Retry-After}; nothing about the rejected mappings is disclosed. */
    @ExceptionHandler(IntegrationRateLimitedException.class)
    ResponseEntity<MessagingProblem> rateLimited(IntegrationRateLimitedException throttled) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(throttled.retryAfterSeconds()))
                .contentType(MediaType.parseMediaType(MessagingApiContract.PROBLEM_MEDIA_TYPE))
                .body(MessagingProblem.of(throttled.code()));
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<MessagingProblem> forbidden() {
        return problem(MessagingErrorCode.FORBIDDEN);
    }

    // The closed contract has no 415/406: an unusable media type is just invalid input.
    @ExceptionHandler({InvalidRequestException.class, HttpMessageNotReadableException.class,
            HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class})
    ResponseEntity<MessagingProblem> invalid() {
        return problem(MessagingErrorCode.INVALID_INPUT);
    }

    /**
     * The last resort (issue 293): any other failure is a closed {@code 500 INTERNAL_ERROR} problem, never Spring's
     * default body, which echoes the path. A database outage and Spring MVC, servlet or security failures are
     * rethrown unchanged, so the outage boundary and Spring keep answering them; audit failures never get here
     * because the audit handler is ordered first.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<MessagingProblem> unexpected(Exception failure) throws Exception {
        if (!UnexpectedFailures.isUnexpected(failure) || databaseUnavailability.isCause(failure)) {
            throw failure;
        }
        UnexpectedFailures.log(failure);
        return problem(MessagingErrorCode.INTERNAL_ERROR);
    }

    private static ResponseEntity<MessagingProblem> problem(MessagingErrorCode code) {
        MessagingProblem body = MessagingProblem.of(code);
        return ResponseEntity.status(HttpStatus.valueOf(body.status()))
                .contentType(MediaType.parseMediaType(MessagingApiContract.PROBLEM_MEDIA_TYPE))
                .body(body);
    }
}
