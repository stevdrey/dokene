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
                "dokene.ai.openai.model=gpt-6-luna"
        ).run(context -> {
            assertThat(context).hasSingleBean(OpenAIClient.class);
            assertThat(context).hasSingleBean(AiProvider.class);
            assertThat(context.getBean(AiProvider.class)).isInstanceOf(OpenAiResponsesApiAdapter.class);
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
}
