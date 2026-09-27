package io.github.stevdrey.dokene.ai.provider.openai;

import com.openai.client.OpenAIClient;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenAiConfiguration.class));

    @Test
    void registersBeansWhenProviderIsOpenAiAndKeyConfigured() {
        runner.withPropertyValues(
                "dokene.ai.provider=openai",
                "dokene.ai.openai.api-key=test-api-key",
                "dokene.ai.openai.model=gpt-6-luna",
                "dokene.ai.openai.max-retries=0"
        ).run(context -> {
            assertThat(context).hasSingleBean(OpenAIClient.class);
            assertThat(context).hasSingleBean(AiProvider.class);
            assertThat(context.getBean(AiProvider.class)).isInstanceOf(OpenAiResponsesApiAdapter.class);
            OpenAiProviderProperties props = context.getBean(OpenAiProviderProperties.class);
            assertThat(props.maxRetries()).isEqualTo(0);
        });
    }

    @Test
    void doesNotRegisterBeansWhenProviderIsFakeOrMissing() {
        runner.withPropertyValues("dokene.ai.provider=fake")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OpenAIClient.class);
                    assertThat(context).doesNotHaveBean(AiProvider.class);
                });

        runner.run(context -> {
            assertThat(context).doesNotHaveBean(OpenAIClient.class);
            assertThat(context).doesNotHaveBean(AiProvider.class);
        });
    }

    @Test
    void failsStartupWhenOpenAiProviderConfiguredWithoutApiKey() {
        runner.withPropertyValues("dokene.ai.provider=openai")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("OpenAI API key must be configured when dokene.ai.provider is set to 'openai'");
                });
    }

    @Test
    void failsStartupWhenMaxRetriesConfiguredGreaterThanZero() {
        runner.withPropertyValues(
                "dokene.ai.provider=openai",
                "dokene.ai.openai.api-key=test-api-key",
                "dokene.ai.openai.max-retries=2"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("dokene.ai.openai.max-retries must be 0 to enforce single-invocation timeout determinism; "
                            + "multi-attempt retries violate request deadline contracts and must be handled at domain level");
        });
    }

    @Test
    void validatesMaxRetriesInPropertiesConstructor() {
        OpenAiProviderProperties defaultProps = new OpenAiProviderProperties("key", null, null, null, null);
        assertThat(defaultProps.maxRetries()).isEqualTo(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new OpenAiProviderProperties("key", null, null, null, 1)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dokene.ai.openai.max-retries must be 0");
    }
}
