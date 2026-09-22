package io.github.stevdrey.dokene.recommendation.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationConfidenceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.001, 0.5, 0.85, 0.999, 1.0})
    void acceptsValidBoundedValues(double valid) {
        RecommendationConfidence confidence = new RecommendationConfidence(valid);
        assertThat(confidence.value()).isEqualTo(valid);
        assertThat(RecommendationConfidence.of(valid)).isEqualTo(confidence);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.0001, -1.0, 1.0001, 100.0, -99.9})
    void rejectsOutOfBoundsValues(double outOfBounds) {
        assertThatThrownBy(() -> new RecommendationConfidence(outOfBounds))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Confidence must be between 0.0 and 1.0")
                .extracting(e -> ((RecommendationValidationException) e).field())
                .isEqualTo("confidence");
    }

    @Test
    void rejectsNaNAndInfinities() {
        assertThatThrownBy(() -> new RecommendationConfidence(Double.NaN))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("finite number");

        assertThatThrownBy(() -> new RecommendationConfidence(Double.POSITIVE_INFINITY))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("finite number");

        assertThatThrownBy(() -> new RecommendationConfidence(Double.NEGATIVE_INFINITY))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("finite number");
    }

    @Test
    void serializesAndDeserializesAsJsonNumber() throws Exception {
        RecommendationConfidence confidence = RecommendationConfidence.of(0.85);
        String json = objectMapper.writeValueAsString(confidence);
        assertThat(json).isEqualTo("0.85");

        RecommendationConfidence deserialized = objectMapper.readValue("0.85", RecommendationConfidence.class);
        assertThat(deserialized).isEqualTo(confidence);
    }
}
