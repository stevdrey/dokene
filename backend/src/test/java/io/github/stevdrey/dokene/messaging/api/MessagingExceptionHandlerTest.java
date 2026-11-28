// ABOUTME: Pins how the message routes answer a provider health refusal (ADR 0038): 503 PROVIDER_UNAVAILABLE as the
// ABOUTME: closed problem body, with Retry-After in whole seconds, rounded up and never below one.
package io.github.stevdrey.dokene.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessagingProblem;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingProviderUnavailableException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;

class MessagingExceptionHandlerTest {
    private final MessagingExceptionHandler handler = new MessagingExceptionHandler(
            new DatabaseUnavailability(Duration.ofSeconds(5)));

    /** covers: spec 0023 AC-4. */
    @Test
    void aProviderHealthRefusalAnswers503WithTheClosedProblemAndNothingElse() {
        ResponseEntity<MessagingProblem> answer = handler.providerUnavailable(
                new MessagingProviderUnavailableException(Duration.ofSeconds(42)));

        assertThat(answer.getStatusCode().value()).isEqualTo(503);
        assertThat(answer.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(answer.getBody()).isEqualTo(MessagingProblem.of(MessagingErrorCode.PROVIDER_UNAVAILABLE));
        assertThat(answer.getHeaders().getFirst("Retry-After")).isEqualTo("42");
    }

    /** covers: spec 0023 AC-4. A zero would tell a client to retry at once, against a provider that is down. */
    @ParameterizedTest
    @CsvSource({"PT0S,1", "PT0.2S,1", "PT1S,1", "PT1.2S,2", "PT5M,300"})
    void theRetryAfterHeaderIsWholeSecondsRoundedUpAndNeverBelowOne(Duration delay, String header) {
        ResponseEntity<MessagingProblem> answer = handler.providerUnavailable(
                new MessagingProviderUnavailableException(delay));

        assertThat(answer.getHeaders().getFirst("Retry-After")).isEqualTo(header);
    }
}
