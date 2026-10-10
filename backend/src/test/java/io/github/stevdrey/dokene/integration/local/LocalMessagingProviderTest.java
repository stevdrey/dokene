// ABOUTME: Unit tests for the local adapter's suffix table and the reports it plans (spec 0003 AC-7).
// ABOUTME: The scheduler is a mock, so this proves the mapping and nothing about threads.
package io.github.stevdrey.dokene.integration.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.stevdrey.dokene.messaging.application.OutboundSendCommand;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalMessagingProviderTest {
    private final LocalDeliveryScheduler scheduler = mock(LocalDeliveryScheduler.class);
    private final LocalMessagingProvider provider = new LocalMessagingProvider(scheduler);
    private final UUID tenantId = UUID.randomUUID();

    @Test
    void suffix00RejectsPermanentlyAsAnInvalidRecipient() {
        assertThat(provider.send(command("+50688880000")))
                .isEqualTo(new ProviderSendResult.RejectedPermanently(FailureCategory.INVALID_RECIPIENT));
        verifyNoInteractions(scheduler);
    }

    @Test
    void suffix01FailsTransientlyAsRateLimited() {
        assertThat(provider.send(command("+50688880001")))
                .isEqualTo(new ProviderSendResult.FailedTransiently(FailureCategory.RATE_LIMITED));
        verifyNoInteractions(scheduler);
    }

    @Test
    void suffix02IsUnknownWithoutAnId() {
        assertThat(provider.send(command("+50688880002")))
                .isInstanceOf(ProviderSendResult.OutcomeUnknown.class)
                .extracting(result -> ((ProviderSendResult.OutcomeUnknown) result).providerMessageId())
                .isEqualTo(java.util.Optional.empty());
        verifyNoInteractions(scheduler);
    }

    @Test
    void suffix04IsUnknownWithAnIdAndADeliveryPlanned() {
        var result = (ProviderSendResult.OutcomeUnknown) provider.send(command("+50688880004"));
        String id = result.providerMessageId().orElseThrow();
        assertThat(id).startsWith("local-");
        verify(scheduler).plan(eq(id), eq(tenantId), eq(List.of(DeliveryStatus.DELIVERED)));
    }

    @Test
    void suffix03IsAcceptedWithDeliveryThenReadPlanned() {
        var result = (ProviderSendResult.Accepted) provider.send(command("+50688880003"));
        verify(scheduler).plan(eq(result.providerMessageId()), eq(tenantId),
                eq(List.of(DeliveryStatus.DELIVERED, DeliveryStatus.READ)));
    }

    @Test
    void everyOtherSuffixIsAcceptedWithADeliveryPlanned() {
        var result = (ProviderSendResult.Accepted) provider.send(command("+50688880055"));
        assertThat(result.providerMessageId()).startsWith("local-").hasSize("local-".length() + 36);
        verify(scheduler).plan(eq(result.providerMessageId()), eq(tenantId), eq(List.of(DeliveryStatus.DELIVERED)));
    }

    @Test
    void theSuffixIsTheLastTwoDigitsIgnoringFormatting() {
        assertThat(LocalMessagingProvider.suffix("+506 8888-00 01")).isEqualTo("01");
        assertThat(LocalMessagingProvider.suffix("7")).isEmpty();
    }

    private OutboundSendCommand command(String phone) {
        return new OutboundSendCommand(phone, "general_follow_up", "es-419", List.of("hola"), "es-419",
                UUID.randomUUID(), tenantId);
    }
}
