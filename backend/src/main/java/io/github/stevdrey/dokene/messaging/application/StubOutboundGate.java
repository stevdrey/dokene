// ABOUTME: Property driven OutboundGate stand in; ENABLED only when the stub property says so.
// ABOUTME: Feature 5 replaces this bean and removes dokene.messaging.outbound-gate.stub.enabled.
package io.github.stevdrey.dokene.messaging.application;

import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(MessagingOutboundGateStubProperties.class)
public class StubOutboundGate implements OutboundGate {
    private final MessagingOutboundGateStubProperties properties;

    public StubOutboundGate(MessagingOutboundGateStubProperties properties) {
        this.properties = Objects.requireNonNull(properties, "Outbound gate properties are required");
    }

    @Override
    public OutboundGateStatus check() {
        return properties.enabled() ? OutboundGateStatus.ENABLED : OutboundGateStatus.OUTBOUND_DISABLED_FOR_TENANT;
    }
}
