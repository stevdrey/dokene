// ABOUTME: Maps message route failures to closed-code problem+json (ADR 0023 §4.4): no detail, ids or free text.
// ABOUTME: Audit persistence failures are left to the shared handler, which answers 503 with an empty body.
package io.github.stevdrey.dokene.messaging.api;

import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessagingProblem;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRateLimitedException;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.ratelimit.RetryAfter;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = MessageController.class)
public class MessagingExceptionHandler {
    @ExceptionHandler(MessagingRefusedException.class)
    ResponseEntity<MessagingProblem> refused(MessagingRefusedException refusal) {
        return problem(refusal.code());
    }

    /** The tenant send rate (ADR 0023 §4.4): the closed problem plus {@code Retry-After} in whole seconds. */
    @ExceptionHandler(MessagingRateLimitedException.class)
    ResponseEntity<MessagingProblem> rateLimited(MessagingRateLimitedException throttled) {
        MessagingProblem body = MessagingProblem.of(throttled.code());
        return ResponseEntity.status(HttpStatus.valueOf(body.status()))
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(RetryAfter.seconds(throttled.retryAfter())))
                .contentType(MediaType.parseMediaType(MessagingApiContract.PROBLEM_MEDIA_TYPE))
                .body(body);
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<MessagingProblem> forbidden() {
        return problem(MessagingErrorCode.FORBIDDEN);
    }

    // A malformed UUID in the path or query, a non-numeric limit or an unusable media type is just invalid input.
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class})
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
