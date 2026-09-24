package io.github.stevdrey.dokene.ai.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticTemplateIntentTest {

    @ParameterizedTest
    @EnumSource(SemanticTemplateIntent.class)
    void parsesValidEnumNames(SemanticTemplateIntent intent) {
        assertThat(SemanticTemplateIntent.from(intent.name())).isEqualTo(intent);
        assertThat(SemanticTemplateIntent.from("  " + intent.name() + "  ")).isEqualTo(intent);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    void rejectsNullOrBlankValues(String invalid) {
        assertThatThrownBy(() -> SemanticTemplateIntent.from(invalid))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Semantic template intent is required")
                .extracting(e -> ((RecommendationValidationException) e).field())
                .isEqualTo("templateIntent");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOM_TEMPLATE", "general_follow_up", "PROMO_CODE_2026"})
    void rejectsUnknownValuesWithoutCoercion(String unknown) {
        assertThatThrownBy(() -> SemanticTemplateIntent.from(unknown))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Unknown semantic template intent: " + unknown)
                .extracting(e -> ((RecommendationValidationException) e).field())
                .isEqualTo("templateIntent");
    }
}
