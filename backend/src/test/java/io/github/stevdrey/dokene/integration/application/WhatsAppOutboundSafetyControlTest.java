// ABOUTME: Pins the send decision order (ADR 0023 §4.3): global switch, tenant switch, then an enabled verified mapping.
// ABOUTME: The verified-mapping gate is composed into evaluate, so the kill switches (#144) cannot leave it out.
package io.github.stevdrey.dokene.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.application.OutboundSafetyControl.Blocked;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WhatsAppOutboundSafetyControlTest {
    private final TenantId tenantId = new TenantId(UUID.randomUUID());
    private final WhatsAppIntegrationRepository integrations = mock(WhatsAppIntegrationRepository.class);
    private final IntegrationReadiness readiness = mock(IntegrationReadiness.class);
    private final WhatsAppOutboundSafetyControl control = new WhatsAppOutboundSafetyControl(integrations, readiness);
    private final WhatsAppIntegration unverified = WhatsAppIntegration.create(tenantId, "100000000000001",
            "200000000000001", null, true, "wa-token", Instant.parse("2026-10-01T10:00:00Z"));

    @Test
    void theGlobalSwitchBlocksFirstWhateverTheTenantAndMappingState() {
        when(readiness.outboundEnabled(tenantId)).thenReturn(true);
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(
                unverified.verifyOwnership(Instant.parse("2026-10-01T11:00:00Z"))));
        assertThat(control.evaluate(tenantId)).isEqualTo(new Blocked(MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY));
    }

    @Test
    void theTenantSwitchIsReadFromReadiness() {
        assertThat(control.tenantGate(tenantId)).contains(new Blocked(MessagingErrorCode.OUTBOUND_DISABLED_FOR_TENANT));
        when(readiness.outboundEnabled(tenantId)).thenReturn(true);
        assertThat(control.tenantGate(tenantId)).isEmpty();
    }

    @Test
    void onlyAnEnabledVerifiedMappingPassesTheIntegrationGate() {
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(unverified));
        assertThat(control.integrationGate(tenantId)).contains(new Blocked(MessagingErrorCode.INTEGRATION_DISABLED));

        WhatsAppIntegration verified = unverified.verifyOwnership(Instant.parse("2026-10-01T11:00:00Z"));
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(verified));
        assertThat(control.integrationGate(tenantId)).isEmpty();

        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(verified.update(verified.phoneNumberId(),
                verified.businessAccountId(), null, false, verified.secretRef(), Instant.parse("2026-10-01T12:00:00Z"))));
        assertThat(control.integrationGate(tenantId)).contains(new Blocked(MessagingErrorCode.INTEGRATION_DISABLED));

        when(integrations.findByTenant(tenantId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> control.integrationGate(tenantId)).isInstanceOf(IllegalStateException.class);
    }
}
