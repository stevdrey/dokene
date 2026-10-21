// ABOUTME: Integration-side implementation of the messaging safety port (ADR 0024 §3): the configured sender identity
// ABOUTME: for the approval fingerprint, and a fail-closed send decision: both kill switches, then a verified mapping.
package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.application.OutboundSafetyControl;
import io.github.stevdrey.dokene.messaging.application.OutboundSwitchProperties;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(OutboundSwitchProperties.class)
public class WhatsAppOutboundSafetyControl implements OutboundSafetyControl {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppOutboundSafetyControl.class);
    private final WhatsAppIntegrationRepository integrations;
    private final OutboundSwitchProperties globalSwitch;

    public WhatsAppOutboundSafetyControl(WhatsAppIntegrationRepository integrations,
            OutboundSwitchProperties globalSwitch) {
        this.integrations = Objects.requireNonNull(integrations, "Integration repository is required");
        this.globalSwitch = Objects.requireNonNull(globalSwitch, "Outbound switch properties are required");
        // The one place the global switch is visible to an operator: the property is bound once and never re-read.
        log.info("Outbound messaging global switch enabled={}", globalSwitch.enabled());
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
     * Send policy steps 3 and 4 (ADR 0023 §4.3), in order, failing closed on the first gate that blocks: the global
     * switch (no repository read), then one read of the tenant's row for the tenant switch, the enabled flag and the
     * ownership proof. Nothing is cached, and a repository failure propagates so the claim rolls back. Dispatch (#138)
     * is the only caller.
     */
    @Override
    public Decision evaluate(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        if (!globalSwitch.enabled()) {
            return new Blocked(MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY);
        }
        WhatsAppIntegration integration = requireIntegration(tenantId);
        if (!integration.outboundEnabled()) {
            return new Blocked(MessagingErrorCode.OUTBOUND_DISABLED_FOR_TENANT);
        }
        return integrationDecision(integration).<Decision>map(blocked -> blocked)
                .orElseGet(() -> new Permitted(senderRevision(integration)));
    }

    /** The sending identity an approval binds; the send adapter compares it with the one approval recorded. */
    public static SenderRevision senderRevision(WhatsAppIntegration integration) {
        return new SenderRevision(integration.id(), integration.phoneNumberId(), integration.businessAccountId(),
                integration.credentialRevision());
    }

    /**
     * Dispatch reaches the gate only for a tenant whose integration existed at approval and nothing deletes the row, so
     * an empty read is a data error, not a policy outcome, as {@link OutboundSafetyControl#evaluate(TenantId)} requires.
     */
    private WhatsAppIntegration requireIntegration(TenantId tenantId) {
        return integrations.findByTenant(tenantId)
                .orElseThrow(() -> new IllegalStateException("No integration is configured"));
    }

    /**
     * The integration half of step 4: a disabled integration, or one whose phone number ownership is not proven for the
     * current configuration (issue 210), is {@code INTEGRATION_DISABLED}. Empty means this gate does not block.
     */
    private static Optional<Blocked> integrationDecision(WhatsAppIntegration integration) {
        return integration.enabled() && integration.ownershipVerified() ? Optional.empty()
                : Optional.of(new Blocked(MessagingErrorCode.INTEGRATION_DISABLED));
    }
}
