// ABOUTME: Pins the Meta error-code table of the send adapter (ADR 0027): category and whether a retry is safe.
// ABOUTME: A 5xx is uncertain unless Meta names an explicitly transient code; a 4xx is always definitive.
package io.github.stevdrey.dokene.integration.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.FailedTransiently;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.OutcomeUnknown;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.RejectedPermanently;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GraphApiSendErrorsTest {

    @ParameterizedTest
    @CsvSource({
            "400, 0, AUTHENTICATION", "400, 3, AUTHENTICATION", "403, 10, AUTHENTICATION",
            "401, 190, AUTHENTICATION", "403, 299, AUTHENTICATION", "403, 131005, AUTHENTICATION",
            "400, 131021, INVALID_RECIPIENT", "400, 131026, INVALID_RECIPIENT",
            "403, 368, POLICY_VIOLATION", "400, 131031, POLICY_VIOLATION", "400, 131047, POLICY_VIOLATION",
            "400, 131050, POLICY_VIOLATION", "400, 130497, POLICY_VIOLATION",
            "400, 100, TEMPLATE_REJECTED", "400, 131008, TEMPLATE_REJECTED", "400, 131009, TEMPLATE_REJECTED",
            "400, 132000, TEMPLATE_REJECTED", "400, 132015, TEMPLATE_REJECTED", "400, 132016, TEMPLATE_REJECTED",
            "400, 135000, TEMPLATE_REJECTED", "400, 131045, UNKNOWN", "400, 99999, UNKNOWN"})
    void definitiveClientErrorsArePermanent(int status, int code, FailureCategory category) {
        assertThat(GraphApiSendErrors.classify(status, OptionalInt.of(code)))
                .isEqualTo(new RejectedPermanently(category));
    }

    @ParameterizedTest
    @CsvSource({
            "400, 4, RATE_LIMITED", "400, 80007, RATE_LIMITED", "400, 130429, RATE_LIMITED",
            "400, 131048, RATE_LIMITED", "400, 131056, RATE_LIMITED", "429, 132000, RATE_LIMITED",
            "400, 1, PROVIDER_UNAVAILABLE", "400, 2, PROVIDER_UNAVAILABLE", "400, 131000, PROVIDER_UNAVAILABLE",
            "400, 131016, PROVIDER_UNAVAILABLE", "400, 131057, PROVIDER_UNAVAILABLE",
            "500, 1, PROVIDER_UNAVAILABLE", "503, 2, PROVIDER_UNAVAILABLE", "503, 131016, PROVIDER_UNAVAILABLE"})
    void refusalsThatProveNothingWasTakenAreTransient(int status, int code, FailureCategory category) {
        assertThat(GraphApiSendErrors.classify(status, OptionalInt.of(code)))
                .isEqualTo(new FailedTransiently(category));
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 425})
    void anIntermediaryThatTookNothingIsTransient(int status) {
        assertThat(GraphApiSendErrors.classify(status, OptionalInt.empty()))
                .isEqualTo(new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE));
    }

    @ParameterizedTest
    @CsvSource({"500, 131000", "500, 4", "502, 190", "503, 132000", "504, 131057"})
    void otherServerErrorsWithACodeAreUncertain(int status, int code) {
        assertThat(GraphApiSendErrors.classify(status, OptionalInt.of(code)))
                .isEqualTo(new OutcomeUnknown(Optional.empty()));
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 301, 302, 307, 500, 502, 503, 504, 599, 600})
    void redirectsAndServerErrorsWithoutACodeAreUncertain(int status) {
        assertThat(GraphApiSendErrors.classify(status, OptionalInt.empty()))
                .isEqualTo(new OutcomeUnknown(Optional.empty()));
    }
}
