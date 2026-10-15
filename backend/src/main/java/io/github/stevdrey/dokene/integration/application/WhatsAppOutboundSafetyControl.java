// ABOUTME: Integration-side implementation of the messaging safety port (ADR 0024 §3): the configured sender identity
// ABOUTME: for the approval fingerprint, and a fail-closed send decision: kill switches (#144), then a verified mapping.
package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.application.OutboundSafetyControl;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class WhatsAppOutboundSafetyControl implements OutboundSafetyControl {
    private final WhatsAppIntegrationRepository integrations;

    public WhatsAppOutboundSafetyControl(WhatsAppIntegrationRepository integrations) {
        this.integrations = Objects.requireNonNull(integrations, "Integration repository is required");
    }

    /**
     * Read from the stored configuration on every call, regardless of {@code enabled} and both kill switches, so an
     * approval can bind the sender while sending is off. Ids and the credential revision only; never the secret
     * reference or a secret value.
     */
    @Override
    public Optional<SenderRevision> configuredSender(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        return integrations.findByTenant(tenantId).map(WhatsAppOutboundSafetyControl::sender);
    }

    /**
     * Fails closed: nothing in Phase 3 may send until the global and tenant kill switches (#144) own their state, so the
     * global switch (send policy step 3, ADR 0023 §4.3) still blocks first. When #144 adds the switches it composes
     * {@link #integrationGate(TenantId)} as the integration half of step 4. Dispatch (#138) is the only caller.
     */
    @Override
    public Decision evaluate(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        return new Blocked(MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY);
    }

    /**
     * The integration half of send policy step 4: a disabled integration, or one whose phone number ownership is not
     * proven for the current credential (issue 210), is {@code INTEGRATION_DISABLED}. Missing configuration throws, as
     * {@link OutboundSafetyControl#evaluate(TenantId)} requires. Empty means this gate does not block.
     */
    public Optional<Blocked> integrationGate(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        WhatsAppIntegration integration = integrations.findByTenant(tenantId)
                .orElseThrow(() -> new IllegalStateException("No integration is configured"));
        return integration.enabled() && integration.ownershipVerified() ? Optional.empty()
                : Optional.of(new Blocked(MessagingErrorCode.INTEGRATION_DISABLED));
    }

    private static SenderRevision sender(WhatsAppIntegration integration) {
        return new SenderRevision(integration.id(), integration.phoneNumberId(), integration.businessAccountId(),
                integration.credentialRevision());
    }
}
