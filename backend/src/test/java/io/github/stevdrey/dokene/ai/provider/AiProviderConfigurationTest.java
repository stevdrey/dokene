package io.github.stevdrey.dokene.ai.provider;

import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.provider.disabled.DisabledAiConfiguration;
import io.github.stevdrey.dokene.ai.provider.disabled.DisabledAiProvider;
import io.github.stevdrey.dokene.ai.provider.fake.DefaultFakeAiProvider;
import io.github.stevdrey.dokene.ai.provider.fake.FakeAiConfiguration;
import io.github.stevdrey.dokene.ai.provider.openai.OpenAiConfiguration;
import io.github.stevdrey.dokene.ai.provider.openai.OpenAiResponsesApiAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProviderConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiProviderSelectionConfiguration.class, OpenAiConfiguration.class,
                    FakeAiConfiguration.class, DisabledAiConfiguration.class);

    @Test
    void unsetPropertyConfiguresDisabledAiProviderFallback() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AiProvider.class);
            assertThat(context.getBean(AiProvider.class)).isInstanceOf(DisabledAiProvider.class);
            assertThat(context).doesNotHaveBean(DefaultFakeAiProvider.class);
            assertThat(context).doesNotHaveBean(OpenAiResponsesApiAdapter.class);
        });
    }

    @Test
    void emptyPropertyConfiguresDisabledAiProviderFallback() {
        runner.withPropertyValues("dokene.ai.provider=").run(context -> {
            assertThat(context).hasSingleBean(AiProvider.class);
            assertThat(context.getBean(AiProvider.class)).isInstanceOf(DisabledAiProvider.class);
            assertThat(context).doesNotHaveBean(DefaultFakeAiProvider.class);
        });
    }

    @Test
    void fakePropertyConfiguresDefaultFakeAiProviderExplicitly() {
        runner.withPropertyValues("dokene.ai.provider=fake").run(context -> {
            assertThat(context).hasSingleBean(AiProvider.class);
            assertThat(context.getBean(AiProvider.class)).isInstanceOf(DefaultFakeAiProvider.class);
            assertThat(context).doesNotHaveBean(DisabledAiProvider.class);
        });
    }

    @Test
    void openAiPropertyConfiguresOpenAiResponsesApiAdapter() {
        runner.withPropertyValues(
                "dokene.ai.provider=openai",
                "dokene.ai.openai.api-key=test-api-key"
        ).run(context -> {
            assertThat(context).hasSingleBean(AiProvider.class);
            assertThat(context.getBean(AiProvider.class)).isInstanceOf(OpenAiResponsesApiAdapter.class);
            assertThat(context).doesNotHaveBean(DisabledAiProvider.class);
            assertThat(context).doesNotHaveBean(DefaultFakeAiProvider.class);
        });
    }

    @Test
    void whitespaceOnlyPropertyConfiguresDisabledAiProviderFallback() {
        runner.withPropertyValues("dokene.ai.provider=   ").run(context -> {
            assertThat(context).hasSingleBean(AiProvider.class);
            assertThat(context.getBean(AiProvider.class)).isInstanceOf(DisabledAiProvider.class);
        });
    }

    @Test
    void providerNameIsCaseInsensitive() {
        runner.withPropertyValues("dokene.ai.provider=FAKE").run(context ->
                assertThat(context.getBean(AiProvider.class)).isInstanceOf(DefaultFakeAiProvider.class));
    }

    @Test
    void unsupportedProviderFailsStartupWithoutExposingSecrets() {
        runner.withPropertyValues(
                "dokene.ai.provider=opneai",
                "dokene.ai.openai.api-key=super-secret-key"
        ).run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            Throwable root = failure;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).contains("opneai", "Supported values: fake, openai");
            for (Throwable t = failure; t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain("super-secret-key");
            }
        });
    }

    @Test
    void paddedProviderValueIsRejected() {
        runner.withPropertyValues("dokene.ai.provider= openai ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void unsupportedProviderFailsStartupEvenWithCustomAiProviderBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(AiProviderSelectionConfiguration.class, CustomAiConfiguration.class,
                        DisabledAiConfiguration.class)
                .withPropertyValues("dokene.ai.provider=opneai")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void customAiProviderBeanTakesPrecedenceOverDisabledFallback() {
        new ApplicationContextRunner()
                .withUserConfiguration(CustomAiConfiguration.class, DisabledAiConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AiProvider.class);
                    assertThat(context.getBean(AiProvider.class)).isSameAs(CustomAiConfiguration.CUSTOM_PROVIDER);
                    assertThat(context).doesNotHaveBean(DisabledAiProvider.class);
                });
    }

    @Test
    void disabledAiProviderThrowsNotAvailableExceptionOnRecommend() {
        DisabledAiProvider provider = new DisabledAiProvider();
        RecommendationContext context = new RecommendationContext(
                new RecommendationContext.TrustedFacts(java.time.LocalDate.of(2026, 9, 28), "DUE",
                        java.util.List.of(io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason.DUE_TODAY),
                        30, java.time.LocalDate.of(2026, 9, 28), true,
                        java.util.List.of(java.time.Instant.parse("2026-09-01T12:00:00Z")),
                        java.util.List.of(io.github.stevdrey.dokene.ai.domain.SemanticAction.GENERAL_CHECK_IN)),
                new RecommendationContext.UntrustedText("Customer", null, java.util.List.of("Purchase")));
        AiRecommendationRequest request = new AiRecommendationRequest(
                AiOperation.NEXT_BEST_ACTION, context, Duration.ofSeconds(5));

        assertThatThrownBy(() -> provider.recommend(request))
                .isInstanceOf(AiProviderException.class)
                .satisfies(ex -> {
                    AiProviderException pe = (AiProviderException) ex;
                    assertThat(pe.category()).isEqualTo(AiFailureCategory.NOT_AVAILABLE);
                    assertThat(pe.metadata().providerId()).isEqualTo("disabled");
                    assertThat(pe.metadata().status()).isEqualTo(io.github.stevdrey.dokene.ai.application.AiCompletionStatus.FAILED);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAiConfiguration {
        static final AiProvider CUSTOM_PROVIDER = request -> null;

        @Bean
        AiProvider customProvider() {
            return CUSTOM_PROVIDER;
        }
    }
}
