// ABOUTME: Pins how the global outbound kill switch binds (spec 0008 AC-1): unset, blank and the off literals are off,
// ABOUTME: the on literals are on, and anything else stops startup naming the property, so a misbinding is never silent.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OutboundSwitchPropertiesTest {
    private static final String PROPERTY = "dokene.messaging.outbound.enabled";

    @Configuration
    @EnableConfigurationProperties(OutboundSwitchProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void unsetMeansOff() {
        runner.run(context -> assertThat(context.getBean(OutboundSwitchProperties.class).enabled()).isFalse());
        assertThat(new OutboundSwitchProperties(null).enabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "false", "off", "no", "0", "FALSE", "Off"})
    void blankAndTheOffLiteralsMeanOff(String value) {
        runner.withPropertyValues(PROPERTY + "=" + value)
                .run(context -> assertThat(context.getBean(OutboundSwitchProperties.class).enabled()).isFalse());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "on", "yes", "1", "TRUE", "On"})
    void theOnLiteralsMeanOn(String value) {
        runner.withPropertyValues(PROPERTY + "=" + value)
                .run(context -> assertThat(context.getBean(OutboundSwitchProperties.class).enabled()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "enabled", "2", "tru"})
    void anyOtherValueFailsStartupNamingTheProperty(String value) {
        runner.withPropertyValues(PROPERTY + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            // The converter names the value; the bind failure one level up names the property an operator must fix.
            assertThat(context.getStartupFailure()).hasStackTraceContaining(PROPERTY)
                    .hasStackTraceContaining("Invalid boolean value '" + value + "'");
        });
    }
}
