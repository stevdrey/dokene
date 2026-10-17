// ABOUTME: The Graph send deadline must fit strictly inside the dispatcher's provider call deadline, or startup fails.
// ABOUTME: Pins the defaults (5s inside 10s) and the refusal of an equal or longer Graph timeout.
package io.github.stevdrey.dokene.integration.whatsapp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.messaging.application.MessagingDispatchProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DispatchDeadlineCheckTest {
    @Test
    void theDefaultsFit() {
        assertThatCode(() -> DispatchDeadlineCheck.require(GraphApiProperties.DEFAULT_TIMEOUT,
                MessagingDispatchProperties.DEFAULT_SEND_TIMEOUT)).doesNotThrowAnyException();
    }

    @Test
    void anEqualOrLongerGraphTimeoutFailsStartup() {
        assertThatThrownBy(() -> DispatchDeadlineCheck.require(Duration.ofSeconds(10), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dokene.messaging.send-timeout");
        assertThatThrownBy(() -> DispatchDeadlineCheck.require(Duration.ofSeconds(5), Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dokene.integration.whatsapp.graph.timeout");
    }
}
