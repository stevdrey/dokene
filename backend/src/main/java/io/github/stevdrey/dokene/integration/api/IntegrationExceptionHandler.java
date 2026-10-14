package io.github.stevdrey.dokene.integration.api;

import io.github.stevdrey.dokene.integration.api.IntegrationApiContract.InvalidRequestException;
import io.github.stevdrey.dokene.integration.application.IntegrationRefusedException;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessagingProblem;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
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
    @ExceptionHandler(IntegrationRefusedException.class)
    ResponseEntity<MessagingProblem> refused(IntegrationRefusedException refusal) {
        return problem(refusal.code());
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

    private static ResponseEntity<MessagingProblem> problem(MessagingErrorCode code) {
        MessagingProblem body = MessagingProblem.of(code);
        return ResponseEntity.status(HttpStatus.valueOf(body.status()))
                .contentType(MediaType.parseMediaType(MessagingApiContract.PROBLEM_MEDIA_TYPE))
                .body(body);
    }
}
