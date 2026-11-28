// ABOUTME: The messaging side of the admission budget's SendSwitch port (ADR 0040, spec 0024 AC-7): sending is allowed
// ABOUTME: only when the outbound safety control permits it, and each of its three blocks answers no.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OutboundSendSwitchTest {
    private final TenantId tenant = TenantId.random();

    @Test
    void sendingIsAllowedWhenBothSwitchesAndTheIntegrationPermitIt() {
        OutboundSendSwitch sendSwitch = new OutboundSendSwitch(control(
                new OutboundSafetyControl.Permitted(
                        new ApprovalFingerprint.SenderRevision(UUID.randomUUID(), "100000000000001", "200000000000002", 1))));

        assertThat(sendSwitch.sendingAllowed(tenant)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = MessagingErrorCode.class, names = {"OUTBOUND_DISABLED_GLOBALLY",
            "OUTBOUND_DISABLED_FOR_TENANT", "INTEGRATION_DISABLED"})
    void eachOfTheThreeBlocksAnswersNo(MessagingErrorCode code) {
        OutboundSendSwitch sendSwitch = new OutboundSendSwitch(control(new OutboundSafetyControl.Blocked(code)));

        assertThat(sendSwitch.sendingAllowed(tenant)).isFalse();
    }

    @Test
    void aControlThatCannotAnswerIsNotGuessedAt() {
        OutboundSendSwitch sendSwitch = new OutboundSendSwitch(new OutboundSafetyControl() {
            @Override
            public Optional<ApprovalFingerprint.SenderRevision> configuredSender(TenantId tenantId) {
                return Optional.empty();
            }

            @Override
            public Decision evaluate(TenantId tenantId) {
                throw new IllegalStateException("switch state missing");
            }
        });

        assertThatThrownBy(() -> sendSwitch.sendingAllowed(tenant)).isInstanceOf(IllegalStateException.class);
    }

    private OutboundSafetyControl control(OutboundSafetyControl.Decision decision) {
        return new OutboundSafetyControl() {
            @Override
            public Optional<ApprovalFingerprint.SenderRevision> configuredSender(TenantId tenantId) {
                return Optional.empty();
            }

            @Override
            public Decision evaluate(TenantId tenantId) {
                assertThat(tenantId).isEqualTo(tenant);
                return decision;
            }
        };
    }
}
