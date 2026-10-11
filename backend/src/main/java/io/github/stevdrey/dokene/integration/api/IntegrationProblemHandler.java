// ABOUTME: Maps every refusal on the integration routes to the ADR 0023 §4.4 problem body and counts it.
// ABOUTME: Scoped to IntegrationController; never echoes input and never logs an exception message (spec 0005 AC-8).
package io.github.stevdrey.dokene.integration.api;

import io.github.stevdrey.dokene.integration.config.IntegrationErrorCode;
import io.github.stevdrey.dokene.integration.config.IntegrationRefusedException;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.web.ProblemResponse;
import io.github.stevdrey.dokene.web.ProblemResponses;
import io.github.stevdrey.dokene.web.StrongEtag;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = IntegrationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IntegrationProblemHandler {
    static final String REFUSALS = "dokene.integration.refusals";
    private static final String UNKNOWN_OPERATION = "unknown";

    private final MeterRegistry registry;

    @Autowired
    public IntegrationProblemHandler(ObjectProvider<MeterRegistry> registry) {
        this(Objects.requireNonNull(registry, "Registry provider is required").getIfAvailable());
    }

    /** A registry, or null when metrics are not configured (the messaging module degrades the same way). */
    public IntegrationProblemHandler(MeterRegistry registry) {
        this.registry = registry;
    }

    @ExceptionHandler(IntegrationRefusedException.class)
    public ResponseEntity<ProblemResponse> refused(IntegrationRefusedException exception, HandlerMethod handler) {
        HttpHeaders headers = new HttpHeaders();
        // A stale If-Match answers with the current ETag so the client can retry without a read (spec 0005 AC-3).
        exception.currentVersion().ifPresent(version -> headers.setETag(StrongEtag.format(version)));
        return problem(status(exception.code()), exception.code(), handler, headers);
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    public ResponseEntity<ProblemResponse> denied(TenantAccessDeniedException exception, HandlerMethod handler) {
        return problem(HttpStatus.FORBIDDEN, IntegrationErrorCode.FORBIDDEN, handler);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ProblemResponse> unreadable(Exception exception, HandlerMethod handler) {
        return problem(HttpStatus.BAD_REQUEST, IntegrationErrorCode.INVALID_INPUT, handler);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException exception,
            HandlerMethod handler) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, IntegrationErrorCode.INVALID_INPUT, handler);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemResponse> notAcceptable(HttpMediaTypeNotAcceptableException exception,
            HandlerMethod handler) {
        return problem(HttpStatus.NOT_ACCEPTABLE, IntegrationErrorCode.INVALID_INPUT, handler);
    }

    /** Spec 0005: 400, 403 by name; the version and cross tenant conflicts are 409; the semantic refusals are 422. */
    static HttpStatus status(IntegrationErrorCode code) {
        return switch (code) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case STALE_VERSION, PHONE_NUMBER_ID_IN_USE, SECRET_REF_IN_USE -> HttpStatus.CONFLICT;
            case INTEGRATION_INCOMPLETE, SECRET_REF_UNRESOLVABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }

    /** The metric tag names the route, not the Java method: read, update or kill-switch. */
    static String operation(HandlerMethod handler) {
        if (handler == null) {
            return UNKNOWN_OPERATION;
        }
        return switch (handler.getMethod().getName()) {
            case "read" -> "read";
            case "update" -> "update";
            case "killSwitch" -> "kill-switch";
            default -> UNKNOWN_OPERATION;
        };
    }

    private ResponseEntity<ProblemResponse> problem(HttpStatus status, IntegrationErrorCode code,
            HandlerMethod handler) {
        return problem(status, code, handler, new HttpHeaders());
    }

    private ResponseEntity<ProblemResponse> problem(HttpStatus status, IntegrationErrorCode code,
            HandlerMethod handler, HttpHeaders headers) {
        if (registry != null) {
            registry.counter(REFUSALS, "operation", operation(handler), "code", code.name()).increment();
        }
        return ProblemResponses.respond(status, code.name(), headers);
    }
}
