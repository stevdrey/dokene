package io.github.stevdrey.dokene.ai.provider.fake;

import io.github.stevdrey.dokene.ai.application.AiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FakeAiConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FakeAiConfiguration.class));

    @Test
    void registersBeanWhenProviderIsExplicitlyFake() {
        runner.withPropertyValues("dokene.ai.provider=fake")
                .run(context -> {
                    assertThat(context).hasSingleBean(AiProvider.class);
                    assertThat(context.getBean(AiProvider.class)).isInstanceOf(DefaultFakeAiProvider.class);
                });
    }

    @Test
    void doesNotRegisterBeanWhenProviderIsMissingOrNotFake() {
        String previous = System.clearProperty("dokene.ai.provider");
        try {
            runner.run(context -> assertThat(context).doesNotHaveBean(AiProvider.class));
        } finally {
            if (previous != null) {
                System.setProperty("dokene.ai.provider", previous);
            }
        }

        runner.withPropertyValues("dokene.ai.provider=openai")
                .run(context -> assertThat(context).doesNotHaveBean(AiProvider.class));

        runner.withPropertyValues("dokene.ai.provider=")
                .run(context -> assertThat(context).doesNotHaveBean(AiProvider.class));
    }
}
