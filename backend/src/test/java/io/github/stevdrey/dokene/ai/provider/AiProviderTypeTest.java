package io.github.stevdrey.dokene.ai.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProviderTypeTest {

    @Test
    void nullAndBlankSelectDisabled() {
        assertThat(AiProviderType.fromProperty(null)).isEqualTo(AiProviderType.DISABLED);
        assertThat(AiProviderType.fromProperty("")).isEqualTo(AiProviderType.DISABLED);
        assertThat(AiProviderType.fromProperty("  ")).isEqualTo(AiProviderType.DISABLED);
    }

    @Test
    void supportedValuesAreMatchedCaseInsensitively() {
        assertThat(AiProviderType.fromProperty("fake")).isEqualTo(AiProviderType.FAKE);
        assertThat(AiProviderType.fromProperty("OpenAI")).isEqualTo(AiProviderType.OPENAI);
    }

    @Test
    void unsupportedValuesAreRejectedWithClearMessage() {
        assertThatThrownBy(() -> AiProviderType.fromProperty("opneai"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dokene.ai.provider")
                .hasMessageContaining("opneai")
                .hasMessageContaining("fake, openai");
        assertThatThrownBy(() -> AiProviderType.fromProperty("disabled"))
                .isInstanceOf(IllegalStateException.class);
    }
}
