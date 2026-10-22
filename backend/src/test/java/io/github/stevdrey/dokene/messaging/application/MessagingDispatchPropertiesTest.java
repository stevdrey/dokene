// ABOUTME: Binds dokene.messaging properties the way Spring Boot does and checks the minimum contact interval rules.
// ABOUTME: Zero stays allowed (spec 0013 AC-6), an absent value takes the 24h default and a negative one is refused.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class MessagingDispatchPropertiesTest {

    /** covers: AC-6 of spec 0013 (a zero interval binds and stays zero). */
    @Test
    void aZeroMinimumContactIntervalBinds() {
        assertThat(bind(Map.of("dokene.messaging.min-contact-interval", "PT0S")).minContactInterval())
                .isEqualTo(Duration.ZERO);
        assertThat(bind(Map.of("dokene.messaging.min-contact-interval", "0s")).minContactInterval())
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void anAbsentMinimumContactIntervalTakesTheDefault() {
        assertThat(bind(Map.of("dokene.messaging.max-send-attempts", "3")).minContactInterval())
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    void aNegativeMinimumContactIntervalIsRefused() {
        assertThatThrownBy(() -> bind(Map.of("dokene.messaging.min-contact-interval", "-PT1H")))
                .isInstanceOf(BindException.class).rootCause()
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be negative");
    }

    private static MessagingDispatchProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("dokene.messaging", MessagingDispatchProperties.class);
    }
}
