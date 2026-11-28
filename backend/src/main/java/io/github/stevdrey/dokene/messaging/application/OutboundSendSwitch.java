// ABOUTME: Messaging's answer to the admission budget's SendSwitch port (ADR 0040): sending is allowed only while the
// ABOUTME: global switch, the tenant switch and the integration state all permit it, read at the moment of asking.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.admission.SendSwitch;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class OutboundSendSwitch implements SendSwitch {
    private final OutboundSafetyControl control;

    OutboundSendSwitch(OutboundSafetyControl control) {
        this.control = Objects.requireNonNull(control, "Outbound safety control is required");
    }

    @Override
    public boolean sendingAllowed(TenantId tenant) {
        return control.evaluate(tenant) instanceof OutboundSafetyControl.Permitted;
    }
}
