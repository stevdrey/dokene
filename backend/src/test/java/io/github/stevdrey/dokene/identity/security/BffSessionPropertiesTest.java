// ABOUTME: Tests the pre login timeout setting under dokene.security.session (issue 311): its default, its bounds,
// ABOUTME: and that an invalid value stops startup with a message that names the property.
package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class BffSessionPropertiesTest {

    private static final String PROPERTY = "dokene.security.session.pre-login-timeout";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void thePreLoginTimeoutDefaultsToFiveMinutes() {
        assertThat(new BffSessionProperties(null, null, null, null, null).preLoginTimeout())
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void acceptsTheBounds() {
        Duration maxLifetime = Duration.ofHours(8);

        assertThat(new BffSessionProperties(maxLifetime, null, null, null, Duration.ofSeconds(1)).preLoginTimeout())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(new BffSessionProperties(maxLifetime, null, null, null, maxLifetime).preLoginTimeout())
                .isEqualTo(maxLifetime);
    }

    /** Under one second would truncate to 0 whole seconds, which the servlet container reads as "never expire". */
    @Test
    void rejectsLessThanOneSecond() {
        assertThatThrownBy(() -> new BffSessionProperties(null, null, null, null, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(PROPERTY);
        assertThatThrownBy(() -> new BffSessionProperties(null, null, null, null, Duration.ofMillis(500)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(PROPERTY);
        assertThatThrownBy(() -> new BffSessionProperties(null, null, null, null, Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(PROPERTY);
    }

    @Test
    void rejectsMoreThanTheMaxLifetime() {
        assertThatThrownBy(() -> new BffSessionProperties(Duration.ofHours(8), null, null, null,
                Duration.ofHours(8).plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(PROPERTY);
    }

    /** covers: AC-8. */
    @ParameterizedTest
    @ValueSource(strings = {"0s", "500ms", "9h"})
    void anInvalidValueStopsStartupAndNamesTheProperty(String value) {
        runner.withPropertyValues(PROPERTY + "=" + value).run(context -> assertThat(context)
                .hasFailed()
                .getFailure().rootCause().hasMessageContaining(PROPERTY));
    }

    @Test
    void aValidValueIsBound() {
        runner.withPropertyValues(PROPERTY + "=90s").run(context -> assertThat(
                context.getBean(BffSessionProperties.class).preLoginTimeout()).isEqualTo(Duration.ofSeconds(90)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BffSessionProperties.class)
    static class PropertiesConfiguration {
    }
}
