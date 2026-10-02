package io.github.stevdrey.dokene.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** The log-format wiring cannot be exercised without the servlet filter, so the shipped property is pinned here. */
class LoggingConfigurationTest {
    private final PropertySource<?> application = load();

    @Test
    void correlationIdIsInjectedThroughTheSupportedCorrelationPattern() {
        assertThat(application.getProperty("logging.pattern.correlation"))
                .isEqualTo("[%X{correlationId:-}] ");
        // the level pattern must keep Spring Boot's default so correlation can coexist with tracing later
        assertThat(application.getProperty("logging.pattern.level")).isNull();
    }

    @Test
    void noActuatorEndpointIsAccessible() {
        assertThat(application.getProperty("management.endpoints.access.default")).isEqualTo("none");
        assertThat(application.getProperty("management.endpoints.web.exposure.include")).isEqualTo("");
    }

    private static PropertySource<?> load() {
        try {
            return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).getFirst();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
