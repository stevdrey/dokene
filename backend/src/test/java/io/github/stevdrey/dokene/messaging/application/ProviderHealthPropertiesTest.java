// ABOUTME: Binds dokene.messaging.provider-health the way Spring Boot does (ADR 0038): the defaults, both modes, and
// ABOUTME: the refusal of an unknown mode or a value outside its bounds, which is what stops startup.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.messaging.domain.ProviderHealthSettings;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthSettings.Mode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ProviderHealthPropertiesTest {
    private static final String PREFIX = "dokene.messaging.provider-health";

    @Test
    void nothingSetGivesEnforceWithTheDocumentedDefaults() {
        assertThat(new ProviderHealthProperties(null, null, null, null, null, null, null, null).settings())
                .isEqualTo(new ProviderHealthSettings(Mode.ENFORCE, 3, Duration.ofMinutes(2), 2,
                        Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(5),
                        Duration.ofSeconds(10)));
    }

    @Test
    void observeModeAndEverySettingBind() {
        ProviderHealthSettings settings = bind(Map.of(PREFIX + ".mode", "observe", PREFIX + ".outage-threshold", "5",
                PREFIX + ".outage-window", "1m", PREFIX + ".recovery-successes", "3", PREFIX + ".stale-after", "10m",
                PREFIX + ".cool-down-initial", "5s", PREFIX + ".cool-down-max", "1m", PREFIX + ".probe-lease",
                "20s")).settings();

        assertThat(settings).isEqualTo(new ProviderHealthSettings(Mode.OBSERVE, 5, Duration.ofMinutes(1), 3,
                Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofSeconds(20)));
    }

    @ParameterizedTest
    @CsvSource({"mode,off", "outage-threshold,0", "outage-window,5s", "recovery-successes,11", "stale-after,90s",
            "cool-down-initial,10m", "cool-down-max,2h", "probe-lease,2m"})
    void aValueOutsideItsBoundsFailsTheBinding(String setting, String value) {
        assertThatThrownBy(() -> bind(Map.of(PREFIX + "." + setting, value))).isInstanceOf(BindException.class);
    }

    private static ProviderHealthProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind(PREFIX, ProviderHealthProperties.class).get();
    }
}
