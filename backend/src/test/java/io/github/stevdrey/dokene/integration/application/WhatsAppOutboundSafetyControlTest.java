// ABOUTME: Pins the send decision order (ADR 0023 §4.3, spec 0008 AC-3): global switch without a read, then one read
// ABOUTME: for the tenant switch and the enabled verified mapping; a failed read propagates and the startup line is logged once.
package io.github.stevdrey.dokene.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.integration.testsupport.LogCapture;
import io.github.stevdrey.dokene.messaging.application.OutboundSafetyControl.Blocked;
import io.github.stevdrey.dokene.messaging.application.OutboundSafetyControl.Permitted;
import io.github.stevdrey.dokene.messaging.application.OutboundSwitchProperties;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.QueryTimeoutException;

class WhatsAppOutboundSafetyControlTest {
    private static final OutboundSwitchProperties GLOBAL_ON = new OutboundSwitchProperties(true);
    private static final OutboundSwitchProperties GLOBAL_OFF = new OutboundSwitchProperties(false);
    private static final String STARTUP_LINE = "Outbound messaging global switch enabled=";

    private final TenantId tenantId = new TenantId(UUID.randomUUID());
    private final WhatsAppIntegrationRepository integrations = mock(WhatsAppIntegrationRepository.class);
    private final WhatsAppIntegration unverified = WhatsAppIntegration.create(tenantId, "100000000000001",
            "200000000000001", null, true, "wa-token", Instant.parse("2026-10-01T10:00:00Z"));
    private final WhatsAppIntegration ready = unverified.verifyOwnership(Instant.parse("2026-10-01T11:00:00Z"))
            .withOutboundEnabled(true, Instant.parse("2026-10-01T12:00:00Z"));

    @Test
    void theGlobalSwitchBlocksFirstWithoutReadingTheIntegration() {
        WhatsAppOutboundSafetyControl control = new WhatsAppOutboundSafetyControl(integrations, GLOBAL_OFF);
        assertThat(control.evaluate(tenantId)).isEqualTo(new Blocked(MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY));
        verifyNoInteractions(integrations);
    }

    @Test
    void aMissingIntegrationIsADataErrorNotAPolicyOutcome() {
        WhatsAppOutboundSafetyControl control = new WhatsAppOutboundSafetyControl(integrations, GLOBAL_ON);
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> control.evaluate(tenantId)).isInstanceOf(IllegalStateException.class)
                .hasMessage("No integration is configured");
    }

    @Test
    void theTenantSwitchIsReadFromTheRowBeforeTheIntegrationState() {
        WhatsAppOutboundSafetyControl control = new WhatsAppOutboundSafetyControl(integrations, GLOBAL_ON);
        // Tenant switch off wins over a disabled, unproven integration: the codes answer in policy order.
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(unverified));
        assertThat(control.evaluate(tenantId)).isEqualTo(new Blocked(MessagingErrorCode.OUTBOUND_DISABLED_FOR_TENANT));
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(
                ready.withOutboundEnabled(false, Instant.parse("2026-10-01T13:00:00Z"))));
        assertThat(control.evaluate(tenantId)).isEqualTo(new Blocked(MessagingErrorCode.OUTBOUND_DISABLED_FOR_TENANT));
    }

    @Test
    void aDisabledOrUnprovenIntegrationBlocksAfterBothSwitches() {
        WhatsAppOutboundSafetyControl control = new WhatsAppOutboundSafetyControl(integrations, GLOBAL_ON);
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(
                unverified.withOutboundEnabled(true, Instant.parse("2026-10-01T12:00:00Z"))));
        assertThat(control.evaluate(tenantId)).isEqualTo(new Blocked(MessagingErrorCode.INTEGRATION_DISABLED));

        WhatsAppIntegration disabled = ready.update(ready.phoneNumberId(), ready.businessAccountId(), null, false,
                ready.secretRef(), Instant.parse("2026-10-01T13:00:00Z"));
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(disabled));
        assertThat(control.evaluate(tenantId)).isEqualTo(new Blocked(MessagingErrorCode.INTEGRATION_DISABLED));
    }

    @Test
    void bothSwitchesOnWithAnEnabledVerifiedMappingPermitsTheConfiguredSenderWithOneRead() {
        WhatsAppOutboundSafetyControl control = new WhatsAppOutboundSafetyControl(integrations, GLOBAL_ON);
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(ready));
        assertThat(control.evaluate(tenantId)).isEqualTo(new Permitted(new SenderRevision(ready.id(),
                "100000000000001", "200000000000001", 1)));
        verify(integrations, times(1)).findByTenant(tenantId);
    }

    @Test
    void aFailedReadPropagatesUnchangedSoTheClaimRollsBack() {
        WhatsAppOutboundSafetyControl control = new WhatsAppOutboundSafetyControl(integrations, GLOBAL_ON);
        QueryTimeoutException failure = new QueryTimeoutException("statement timeout");
        when(integrations.findByTenant(tenantId)).thenThrow(failure);
        assertThatThrownBy(() -> control.evaluate(tenantId)).isSameAs(failure);
    }

    @Test
    void theConfiguredSenderIgnoresBothSwitchesAndTheIntegrationState() {
        WhatsAppOutboundSafetyControl control = new WhatsAppOutboundSafetyControl(integrations, GLOBAL_OFF);
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(unverified));
        assertThat(control.configuredSender(tenantId)).contains(new SenderRevision(unverified.id(),
                "100000000000001", "200000000000001", 1));

        when(integrations.findByTenant(tenantId)).thenReturn(Optional.empty());
        assertThat(control.configuredSender(tenantId)).isEmpty();
    }

    @Configuration
    @EnableConfigurationProperties(OutboundSwitchProperties.class)
    static class ControlOnly {
        @Bean
        WhatsAppIntegrationRepository integrations() {
            return mock(WhatsAppIntegrationRepository.class);
        }

        @Bean
        WhatsAppOutboundSafetyControl control(WhatsAppIntegrationRepository integrations,
                OutboundSwitchProperties properties) {
            return new WhatsAppOutboundSafetyControl(integrations, properties);
        }
    }

    @Test
    void startupLogsTheBoundSwitchStateExactlyOnceAndNothingPerEvaluation() {
        try (LogCapture logs = new LogCapture()) {
            new ApplicationContextRunner().withUserConfiguration(ControlOnly.class).run(context -> {
                assertThat(context).hasNotFailed();
                WhatsAppOutboundSafetyControl control = context.getBean(WhatsAppOutboundSafetyControl.class);
                control.evaluate(tenantId);
                control.evaluate(tenantId);
            });
            assertThat(logs.events()).filteredOn(event -> event.getFormattedMessage().contains(STARTUP_LINE))
                    .singleElement().satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                        assertThat(event.getLoggerName()).isEqualTo(WhatsAppOutboundSafetyControl.class.getName());
                        assertThat(event.getFormattedMessage()).isEqualTo(STARTUP_LINE + "false");
                    });
            assertThat(logs.events()).map(ILoggingEvent::getLoggerName)
                    .filteredOn(WhatsAppOutboundSafetyControl.class.getName()::equals).hasSize(1);
        }
        try (LogCapture logs = new LogCapture()) {
            new ApplicationContextRunner().withUserConfiguration(ControlOnly.class)
                    .withPropertyValues("dokene.messaging.outbound.enabled=true")
                    .run(context -> assertThat(context).hasNotFailed());
            assertThat(logs.text()).contains(STARTUP_LINE + "true");
        }
    }
}
