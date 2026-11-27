// ABOUTME: The provider health probe lease must be strictly longer than the Graph timeout, or startup fails.
// ABOUTME: Pins the defaults (10s over 5s) and the refusal of an equal or shorter lease.
package io.github.stevdrey.dokene.integration.whatsapp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.messaging.application.ProviderHealthProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProbeLeaseCheckTest {
    @Test
    void theDefaultsFit() {
        assertThatCode(() -> ProbeLeaseCheck.require(GraphApiProperties.DEFAULT_TIMEOUT,
                ProviderHealthProperties.DEFAULT_PROBE_LEASE)).doesNotThrowAnyException();
    }

    @Test
    void anEqualOrShorterLeaseFailsStartup() {
        assertThatThrownBy(() -> ProbeLeaseCheck.require(Duration.ofSeconds(5), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dokene.messaging.provider-health.probe-lease");
        assertThatThrownBy(() -> ProbeLeaseCheck.require(Duration.ofSeconds(5), Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dokene.integration.whatsapp.graph.timeout");
    }
}
