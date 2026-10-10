// ABOUTME: Unit tests for MessagingProblemHandler: the closed status mapping, the problem body and the refusal counter.
// ABOUTME: Covers spec 0002 AC-4 (status per code), AC-5 (body shape) and AC-9 (counter tagged operation and code).
package io.github.stevdrey.dokene.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.web.ProblemResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.method.HandlerMethod;

class MessagingProblemHandlerTest {
    private static final EnumSet<MessagingErrorCode> NAMED_STATUSES = EnumSet.of(MessagingErrorCode.INVALID_INPUT,
            MessagingErrorCode.FORBIDDEN, MessagingErrorCode.NOT_FOUND, MessagingErrorCode.RATE_LIMITED,
            MessagingErrorCode.PROVIDER_UNAVAILABLE);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MessagingProblemHandler handler = new MessagingProblemHandler(registry);

    @Test
    void mapsTheFiveNamedCodesToTheirOwnStatuses() {
        assertThat(MessagingProblemHandler.status(MessagingErrorCode.INVALID_INPUT)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(MessagingProblemHandler.status(MessagingErrorCode.FORBIDDEN)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(MessagingProblemHandler.status(MessagingErrorCode.NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(MessagingProblemHandler.status(MessagingErrorCode.RATE_LIMITED))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(MessagingProblemHandler.status(MessagingErrorCode.PROVIDER_UNAVAILABLE))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(MessagingErrorCode.class)
    void everyOtherCodeIsAConflict(MessagingErrorCode code) {
        if (NAMED_STATUSES.contains(code)) {
            return;
        }
        assertThat(MessagingProblemHandler.status(code)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aRefusalBecomesAProblemBodyWithExactlyTheFourFieldsAndTheProblemContentType() throws Exception {
        ResponseEntity<ProblemResponse> response = handler.refused(
                new MessagingRefusedException(MessagingErrorCode.STALE_VERSION), handler("approval"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isEqualTo(new ProblemResponse("about:blank", "Conflict", 409, "STALE_VERSION"));
        assertThat(ProblemResponse.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("type", "title", "status", "code");
    }

    @Test
    void countsEachRefusalByOperationAndCode() throws Exception {
        handler.refused(new MessagingRefusedException(MessagingErrorCode.STALE_VERSION), handler("approval"));
        handler.refused(new MessagingRefusedException(MessagingErrorCode.STALE_VERSION), handler("approval"));
        handler.refused(new MessagingRefusedException(MessagingErrorCode.NOT_FOUND), handler("detail"));

        assertThat(registry.get(MessagingProblemHandler.REFUSALS).tags("operation", "approval", "code", "STALE_VERSION")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get(MessagingProblemHandler.REFUSALS).tags("operation", "detail", "code", "NOT_FOUND")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find(MessagingProblemHandler.REFUSALS).counters()).hasSize(2);
    }

    @Test
    void tagsTheOperationAsUnknownWhenSpringGivesNoHandler() {
        handler.denied(new TenantAccessDeniedException("no"), null);

        assertThat(registry.get(MessagingProblemHandler.REFUSALS).tags("operation", "unknown", "code", "FORBIDDEN")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void worksWithoutARegistry() throws Exception {
        MessagingProblemHandler quiet = new MessagingProblemHandler((io.micrometer.core.instrument.MeterRegistry) null);

        ResponseEntity<ProblemResponse> response = quiet.denied(new TenantAccessDeniedException("no"), handler("cancellation"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void deniedAccessIsForbiddenWhateverTheServiceMessageSaid() throws Exception {
        ResponseEntity<ProblemResponse> response = handler.denied(
                new TenantAccessDeniedException("membership 123 lacks MESSAGE_APPROVE"), handler("approval"));

        assertThat(response.getBody()).isEqualTo(new ProblemResponse("about:blank", "Forbidden", 403, "FORBIDDEN"));
    }

    @Test
    void contentNegotiationFailuresAreInvalidInputWithTheirOwnStatuses() throws Exception {
        ResponseEntity<ProblemResponse> unsupported = handler.unsupportedMediaType(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                handler("submit"));
        ResponseEntity<ProblemResponse> notAcceptable = handler.notAcceptable(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)), handler("detail"));

        assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(unsupported.getBody()).isEqualTo(
                new ProblemResponse("about:blank", "Unsupported Media Type", 415, "INVALID_INPUT"));
        assertThat(notAcceptable.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(notAcceptable.getBody().code()).isEqualTo("INVALID_INPUT");
    }

    /** A HandlerMethod over a real controller method, so the operation tag is the route method name. */
    private static HandlerMethod handler(String methodName) throws Exception {
        Method method = null;
        for (Method candidate : MessageController.class.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)) {
                method = candidate;
                break;
            }
        }
        if (method == null) {
            throw new NoSuchMethodException(methodName);
        }
        return new HandlerMethod(new Object(), method);
    }
}
