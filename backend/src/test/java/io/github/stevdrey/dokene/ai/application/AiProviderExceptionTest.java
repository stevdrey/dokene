package io.github.stevdrey.dokene.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AiProviderExceptionTest {
    private static AiInvocationMetadata failed() {
        return new AiInvocationMetadata("p", "m", null, Duration.ofMillis(5), null, AiCompletionStatus.FAILED);
    }

    @Test
    void rejectionDefaultsToNullAndCarriesNoModelText() {
        assertThat(new AiProviderException(AiFailureCategory.INVALID_STRUCTURED_RESPONSE, failed()).rejection()).isNull();
        var rejected = new AiProviderException(AiFailureCategory.INVALID_STRUCTURED_RESPONSE, failed(),
                AiOutputRejection.UNSAFE_CONTENT);
        assertThat(rejected.rejection()).isEqualTo(AiOutputRejection.UNSAFE_CONTENT);
        assertThat(rejected.getMessage()).isEqualTo("AI provider invocation failed: INVALID_STRUCTURED_RESPONSE");
    }

    @Test
    void rejectionIsOnlyLegalForInvalidStructuredResponses() {
        assertThatThrownBy(() -> new AiProviderException(AiFailureCategory.UNAVAILABLE, failed(),
                AiOutputRejection.UNSAFE_CONTENT)).isInstanceOf(IllegalArgumentException.class);
    }
}
