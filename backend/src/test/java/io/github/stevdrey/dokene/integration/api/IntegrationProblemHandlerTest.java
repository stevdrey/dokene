// ABOUTME: Unit tests for the status table and the metric operation tag of the integration refusals (spec 0005 AC-8).
// ABOUTME: Counts land in dokene.integration.refusals{operation,code}; the body never carries input.
package io.github.stevdrey.dokene.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.stevdrey.dokene.integration.config.IntegrationConfigurationService;
import io.github.stevdrey.dokene.integration.config.IntegrationErrorCode;
import io.github.stevdrey.dokene.integration.config.IntegrationRefusedException;
import io.github.stevdrey.dokene.web.ProblemResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;

class IntegrationProblemHandlerTest {

    @Test
    void mapsEveryCodeToItsStatus() {
        assertThat(IntegrationProblemHandler.status(IntegrationErrorCode.INVALID_INPUT)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(IntegrationProblemHandler.status(IntegrationErrorCode.FORBIDDEN)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(IntegrationProblemHandler.status(IntegrationErrorCode.STALE_VERSION)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(IntegrationProblemHandler.status(IntegrationErrorCode.PHONE_NUMBER_ID_IN_USE))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(IntegrationProblemHandler.status(IntegrationErrorCode.SECRET_REF_IN_USE)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(IntegrationProblemHandler.status(IntegrationErrorCode.INTEGRATION_INCOMPLETE))
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(IntegrationProblemHandler.status(IntegrationErrorCode.SECRET_REF_UNRESOLVABLE))
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void rendersTheProblemBodyAndCountsTheRefusalByRouteName() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntegrationProblemHandler handler = new IntegrationProblemHandler(registry);
        HandlerMethod killSwitch = new HandlerMethod(
                new IntegrationController(mock(IntegrationConfigurationService.class)),
                IntegrationController.class.getMethod("killSwitch", String.class, OutboundSwitchRequest.class));

        ResponseEntity<ProblemResponse> response = handler.refused(
                new IntegrationRefusedException(IntegrationErrorCode.STALE_VERSION, 4), killSwitch);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"4\"");
        assertThat(handler.refused(new IntegrationRefusedException(IntegrationErrorCode.INTEGRATION_INCOMPLETE),
                killSwitch).getHeaders().getETag()).isNull();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isEqualTo(new ProblemResponse("about:blank", "Conflict", 409, "STALE_VERSION"));
        assertThat(registry.counter(IntegrationProblemHandler.REFUSALS, "operation", "kill-switch",
                "code", "STALE_VERSION").count()).isEqualTo(1.0);
        assertThat(IntegrationProblemHandler.operation(null)).isEqualTo("unknown");
    }
}
