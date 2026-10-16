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
    private final IntegrationReadiness readiness;

    public WhatsAppOutboundSafetyControl(WhatsAppIntegrationRepository integrations, IntegrationReadiness readiness) {
        this.integrations = Objects.requireNonNull(integrations, "Integration repository is required");
        this.readiness = Objects.requireNonNull(readiness, "Readiness is required");
    }

    /**
     * Read from the stored configuration on every call, regardless of {@code enabled} and both kill switches, so an
     * approval can bind the sender while sending is off. Ids and the credential revision only; never the secret
     * reference or a secret value.
     */
    @Override
    public Optional<SenderRevision> configuredSender(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        return integrations.findByTenant(tenantId).map(WhatsAppOutboundSafetyControl::senderRevision);
    }

    /**
     * Send policy steps 3 and 4 (ADR 0023 §4.3), in order, failing closed on the first gate that blocks. Read on every
     * call, never cached. Dispatch (#138) is the only caller. The kill switches (#144) replace {@link #globalGate()} and
     * the readiness behind {@link #tenantGate(TenantId)}; the verified-mapping gate is already composed, so it cannot
     * be left out when they arrive.
     */
    @Override
    public Decision evaluate(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Optional<Blocked> blocked = globalGate().or(() -> tenantGate(tenantId)).or(() -> integrationGate(tenantId));
        if (blocked.isPresent()) {
            return blocked.get();
        }
        return new Permitted(configuredSender(tenantId)
                .orElseThrow(() -> new IllegalStateException("No integration is configured")));
    }

    /** Step 3: nothing in Phase 3 may send until the global kill switch (#144) owns its state. */
    Optional<Blocked> globalGate() {
        return Optional.of(new Blocked(MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY));
    }

    /** The tenant half of step 4: the tenant outbound switch, reported off until #144 persists it. */
    Optional<Blocked> tenantGate(TenantId tenantId) {
        return readiness.outboundEnabled(tenantId) ? Optional.empty()
                : Optional.of(new Blocked(MessagingErrorCode.OUTBOUND_DISABLED_FOR_TENANT));
    }

    /**
     * The integration half of step 4: a disabled integration, or one whose phone number ownership is not proven for
     * the current configuration (issue 210), is {@code INTEGRATION_DISABLED}. Missing configuration throws, as
     * {@link OutboundSafetyControl#evaluate(TenantId)} requires. Empty means this gate does not block.
     */
    public Optional<Blocked> integrationGate(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        WhatsAppIntegration integration = integrations.findByTenant(tenantId)
                .orElseThrow(() -> new IllegalStateException("No integration is configured"));
        return integration.enabled() && integration.ownershipVerified() ? Optional.empty()
                : Optional.of(new Blocked(MessagingErrorCode.INTEGRATION_DISABLED));
    }

    /** The sending identity an approval binds; the send adapter compares it with the one approval recorded. */
    public static SenderRevision senderRevision(WhatsAppIntegration integration) {
        return new SenderRevision(integration.id(), integration.phoneNumberId(), integration.businessAccountId(),
                integration.credentialRevision());
    }
}
