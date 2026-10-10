// ABOUTME: Unit tests for the outbound gate stand in: closed unless the stub property opens it (spec 0003 AC-2).
// ABOUTME: Feature 5 deletes this with the stub.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StubOutboundGateTest {

    @Test
    void closedByDefaultAndOpenOnlyWhenConfigured() {
        assertThat(new StubOutboundGate(new MessagingOutboundGateStubProperties(false)).check())
                .isEqualTo(OutboundGateStatus.OUTBOUND_DISABLED_FOR_TENANT);
        assertThat(new StubOutboundGate(new MessagingOutboundGateStubProperties(true)).check())
                .isEqualTo(OutboundGateStatus.ENABLED);
    }
}
