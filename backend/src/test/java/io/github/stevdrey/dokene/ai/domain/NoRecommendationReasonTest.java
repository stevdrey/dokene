package io.github.stevdrey.dokene.ai.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoRecommendationReasonTest {

    @ParameterizedTest
    @EnumSource(NoRecommendationReason.class)
    void parsesValidEnumNames(NoRecommendationReason reason) {
        assertThat(NoRecommendationReason.from(reason.name())).isEqualTo(reason);
        assertThat(NoRecommendationReason.from("  " + reason.name() + "  ")).isEqualTo(reason);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    void rejectsNullOrBlankValues(String invalid) {
        assertThatThrownBy(() -> NoRecommendationReason.from(invalid))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("No-recommendation reason is required")
                .extracting(e -> ((RecommendationValidationException) e).field())
                .isEqualTo("reason");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN_REASON", "insufficient_history", "REFUSED_TO_ANSWER"})
    void rejectsUnknownValuesWithoutCoercion(String unknown) {
        assertThatThrownBy(() -> NoRecommendationReason.from(unknown))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Unknown no-recommendation reason: " + unknown)
                .extracting(e -> ((RecommendationValidationException) e).field())
                .isEqualTo("reason");
    }
}
