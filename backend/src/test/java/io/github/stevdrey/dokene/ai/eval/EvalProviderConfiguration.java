package io.github.stevdrey.dokene.ai.eval;

import io.github.stevdrey.dokene.ai.application.AiProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Wraps the application's {@link AiProvider} bean in a {@link RecordingAiProvider}. In deterministic mode the bean
 * is replaced by the scripted provider; in live mode the real adapter is kept and merely observed. The application
 * services under evaluation are therefore the production ones, wired exactly as in the application.
 */
@TestConfiguration
public class EvalProviderConfiguration {
    public static final String MODE_PROPERTY = "dokene.eval.mode";

    @Bean
    static BeanPostProcessor evalProviderRecorder(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof AiProvider provider) || bean instanceof RecordingAiProvider) {
                    return bean;
                }
                boolean deterministic = !"live".equals(environment.getProperty(MODE_PROPERTY, "deterministic"));
                return new RecordingAiProvider(deterministic
                        ? new ScriptedEvalAiProvider(EvalDatasetLoader.loadDefault()) : provider);
            }
        };
    }
}
