// ABOUTME: Maps every refusal on the messaging routes to the ADR 0023 §4.4 problem body and counts it.
// ABOUTME: Scoped to MessageController so Phase 1 and 2 routes keep their empty error bodies.
package io.github.stevdrey.dokene.messaging.api;

import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.web.ProblemResponse;
import io.github.stevdrey.dokene.web.ProblemResponses;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = MessageController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MessagingProblemHandler {
    static final String REFUSALS = "dokene.messaging.refusals";
    private static final String UNKNOWN_OPERATION = "unknown";

    private final MeterRegistry registry;

    @Autowired
    public MessagingProblemHandler(ObjectProvider<MeterRegistry> registry) {
        this(Objects.requireNonNull(registry, "Registry provider is required").getIfAvailable());
    }

    /** A registry, or null when metrics are not configured (the AI module degrades the same way). */
    public MessagingProblemHandler(MeterRegistry registry) {
        this.registry = registry;
    }

    @ExceptionHandler(MessagingRefusedException.class)
    public ResponseEntity<ProblemResponse> refused(MessagingRefusedException exception, HandlerMethod handler) {
        HttpHeaders headers = new HttpHeaders();
        // Whole seconds, rounded up, never below one (spec 0003 step 2b).
        exception.retryAfter().ifPresent(wait -> headers.set(HttpHeaders.RETRY_AFTER,
                Long.toString(Math.max(1L, (wait.toMillis() + 999L) / 1000L))));
        return problem(status(exception.code()), exception.code(), handler, headers);
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    public ResponseEntity<ProblemResponse> denied(TenantAccessDeniedException exception, HandlerMethod handler) {
        return problem(HttpStatus.FORBIDDEN, MessagingErrorCode.FORBIDDEN, handler);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            HandlerMethodValidationException.class})
    public ResponseEntity<ProblemResponse> unreadable(Exception exception, HandlerMethod handler) {
        return problem(HttpStatus.BAD_REQUEST, MessagingErrorCode.INVALID_INPUT, handler);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException exception,
            HandlerMethod handler) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, MessagingErrorCode.INVALID_INPUT, handler);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemResponse> notAcceptable(HttpMediaTypeNotAcceptableException exception,
            HandlerMethod handler) {
        return problem(HttpStatus.NOT_ACCEPTABLE, MessagingErrorCode.INVALID_INPUT, handler);
    }

    /** ADR 0023 §4.4: 400, 403, 404, 429 and 503 by name; every policy and concurrency refusal is a 409. */
    static HttpStatus status(MessagingErrorCode code) {
        return switch (code) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case STALE_VERSION, IDEMPOTENCY_KEY_REUSED, INVALID_TRANSITION, CUSTOMER_ARCHIVED, DO_NOT_CONTACT,
                 NO_CONTACT_CONSENT, CONTACT_NOT_OWNED, FOLLOW_UP_INELIGIBLE, MESSAGE_ALREADY_OPEN,
                 CONTACT_FREQUENCY_EXCEEDED, TEMPLATE_NOT_MAPPED, BODY_REJECTED, OUTBOUND_DISABLED_GLOBALLY,
                 OUTBOUND_DISABLED_FOR_TENANT, INTEGRATION_DISABLED, SEND_ATTEMPTS_EXHAUSTED -> HttpStatus.CONFLICT;
        };
    }

    private ResponseEntity<ProblemResponse> problem(HttpStatus status, MessagingErrorCode code, HandlerMethod handler) {
        return problem(status, code, handler, new HttpHeaders());
    }

    private ResponseEntity<ProblemResponse> problem(HttpStatus status, MessagingErrorCode code, HandlerMethod handler,
            HttpHeaders headers) {
        if (registry != null) {
            registry.counter(REFUSALS, "operation", operation(handler), "code", code.name()).increment();
        }
        return ProblemResponses.respond(status, code.name(), headers);
    }

    private static String operation(HandlerMethod handler) {
        return handler == null ? UNKNOWN_OPERATION : handler.getMethod().getName();
    }
}
