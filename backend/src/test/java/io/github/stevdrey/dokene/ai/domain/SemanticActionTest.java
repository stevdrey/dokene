package io.github.stevdrey.dokene.ai.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticActionTest {

    @ParameterizedTest
    @EnumSource(SemanticAction.class)
    void parsesValidEnumNames(SemanticAction action) {
        assertThat(SemanticAction.from(action.name())).isEqualTo(action);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    void rejectsNullOrBlankValues(String invalid) {
        assertThatThrownBy(() -> SemanticAction.from(invalid))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Semantic action is required")
                .extracting(e -> ((RecommendationValidationException) e).field())
                .isEqualTo("action");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN_ACTION", "repeat_purchase_follow_up", "DELETE_ACCOUNT", "SEND_EMAIL", " REPEAT_PURCHASE_FOLLOW_UP "})
    void rejectsUnknownValuesWithoutCoercion(String unknown) {
        assertThatThrownBy(() -> SemanticAction.from(unknown))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Unknown semantic action: " + unknown)
                .extracting(e -> ((RecommendationValidationException) e).field())
                .isEqualTo("action");
    }
}
