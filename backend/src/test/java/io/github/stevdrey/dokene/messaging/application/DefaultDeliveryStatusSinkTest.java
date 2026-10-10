// ABOUTME: Unit tests for DefaultDeliveryStatusSink with the repository and contexts mocked at the boundary.
// ABOUTME: Covers AC-7 of spec 0001: context refusals, NotFound, the touch rule and the ignored report path.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.followup.application.FollowUpTouchRecorder;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;
import io.github.stevdrey.dokene.messaging.domain.MessageChannel;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.tenant.application.ProviderContext;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultDeliveryStatusSinkTest {
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private final OutboundMessageRepository messages = mock(OutboundMessageRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final FollowUpTouchRecorder touches = mock(FollowUpTouchRecorder.class);
    private final MessageAuditPort audit = mock(MessageAuditPort.class);
    private final ProviderContextProvider providerContexts = mock(ProviderContextProvider.class);
    private final TenantContextProvider tenantContexts = mock(TenantContextProvider.class);
    private final DefaultDeliveryStatusSink sink = new DefaultDeliveryStatusSink(messages, customers, touches, audit,
            providerContexts, tenantContexts, Clock.fixed(NOW, ZoneOffset.UTC));
    private final TenantId tenantId = TenantId.random();
    private final ProviderContext provider = new ProviderContext(tenantId);

    @BeforeEach
    void bindContexts() throws Throwable {
        when(providerContexts.current()).thenReturn(Optional.of(provider));
        when(tenantContexts.current()).thenReturn(Optional.empty());
        when(tenantContexts.callWithTenantId(eq(tenantId), any())).thenAnswer(invocation -> {
            TenantContextProvider.ScopedOperation<?, ?> operation = invocation.getArgument(1);
            return operation.execute();
        });
    }

    @Test
    void refusesToRunWithoutAProviderContext() {
        when(providerContexts.current()).thenReturn(Optional.empty());

        assertRefused(() -> sink.apply(report("wamid.1", DeliveryStatus.DELIVERED)));
        verifyNoInteractions(messages, customers, touches, audit);
    }

    @Test
    void refusesToRunWhileAMemberContextIsBound() {
        when(tenantContexts.current()).thenReturn(Optional.of(mock(TenantContext.class)));

        assertRefused(() -> sink.apply(report("wamid.1", DeliveryStatus.DELIVERED)));
        verifyNoInteractions(messages, customers, touches, audit);
    }

    @Test
    void returnsNotFoundForAnUnknownProviderMessageIdWithoutLocking() {
        when(messages.findIdsByProviderMessageId(tenantId.value(), "wamid.none")).thenReturn(Optional.empty());

        assertThat(sink.apply(report("wamid.none", DeliveryStatus.DELIVERED)))
                .isInstanceOf(DeliveryStatusResult.NotFound.class);
        verify(customers, never()).findByIdForUpdate(any(), any());
        verifyNoInteractions(touches, audit);
    }

    @Test
    void returnsNotFoundWhenTheLockedRowNoLongerCarriesThatProviderId() {
        OutboundMessage sent = message(MessageStatus.SENT, false, 1);
        stubLookup(sent, "wamid.other");

        assertThat(sink.apply(report("wamid.other", DeliveryStatus.DELIVERED)))
                .isInstanceOf(DeliveryStatusResult.NotFound.class);
        verify(messages, never()).update(any(), any());
        verifyNoInteractions(touches, audit);
    }

    @Test
    void appliesADeliveredReportFromSentAndAuditsItWithoutTouchingTheCadence() {
        OutboundMessage sent = message(MessageStatus.SENT, false, 1);
        stubLookup(sent, sent.providerMessageId());
        when(messages.update(eq(sent), any())).thenAnswer(invocation -> invocation.getArgument(1));

        DeliveryStatusResult result = sink.apply(report(sent.providerMessageId(), DeliveryStatus.DELIVERED));

        assertThat(result).isEqualTo(new DeliveryStatusResult.Applied(MessageStatus.SENT, MessageStatus.DELIVERED));
        ArgumentCaptor<MessageEvent> event = ArgumentCaptor.forClass(MessageEvent.class);
        verify(messages).appendEvent(event.capture());
        assertThat(event.getValue().applied()).isTrue();
        assertThat(event.getValue().sequenceNumber()).isEqualTo(sent.version() + 1);
        verify(audit).deliveryUpdated(eq(provider), eq(event.getValue()));
        verifyNoInteractions(touches);
    }

    @Test
    void touchesTheCadenceWhenALateReportResolvesAnUnknownSendOutcome() {
        OutboundMessage unknown = message(MessageStatus.SENDING, true, 1);
        stubLookup(unknown, unknown.providerMessageId());
        when(messages.update(eq(unknown), any())).thenAnswer(invocation -> invocation.getArgument(1));

        DeliveryStatusResult result = sink.apply(report(unknown.providerMessageId(), DeliveryStatus.DELIVERED));

        assertThat(result).isEqualTo(new DeliveryStatusResult.Applied(MessageStatus.SENDING, MessageStatus.DELIVERED));
        verify(touches).recordOutboundMessage(new CustomerId(unknown.customerId()), NOW.minusSeconds(30));
        verify(audit).deliveryUpdated(eq(provider), any());
    }

    @Test
    void recordsAnOutOfOrderReportAsANonAppliedEventWithAVersionBumpAndNoAudit() {
        OutboundMessage read = message(MessageStatus.READ, false, 1);
        stubLookup(read, read.providerMessageId());
        when(messages.update(eq(read), any())).thenAnswer(invocation -> invocation.getArgument(1));

        DeliveryStatusResult result = sink.apply(report(read.providerMessageId(), DeliveryStatus.DELIVERED));

        assertThat(result).isInstanceOf(DeliveryStatusResult.Ignored.class);
        ArgumentCaptor<OutboundMessage> next = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(messages).update(eq(read), next.capture());
        assertThat(next.getValue().status()).isEqualTo(MessageStatus.READ);
        assertThat(next.getValue().version()).isEqualTo(read.version() + 1);
        ArgumentCaptor<MessageEvent> event = ArgumentCaptor.forClass(MessageEvent.class);
        verify(messages).appendEvent(event.capture());
        assertThat(event.getValue().applied()).isFalse();
        assertThat(event.getValue().sequenceNumber()).isEqualTo(read.version() + 1);
        verifyNoInteractions(touches, audit);
    }

    private void stubLookup(OutboundMessage stored, String lookupId) {
        when(messages.findIdsByProviderMessageId(tenantId.value(), lookupId)).thenReturn(Optional.of(
                new OutboundMessageRepository.ProviderMessageRef(stored.id(), stored.customerId())));
        Customer customer = Customer.create(new CustomerId(stored.customerId()), tenantId, "Customer", null,
                List.of(CustomerPhone.create("+56912345678", true)), NOW);
        when(customers.findByIdForUpdate(tenantId, new CustomerId(stored.customerId()))).thenReturn(Optional.of(customer));
        when(messages.findByIdForUpdate(tenantId.value(), stored.id())).thenReturn(Optional.of(stored));
    }

    private OutboundMessage message(MessageStatus status, boolean outcomeUnknown, int attemptCount) {
        boolean sent = Set.of(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ).contains(status);
        return new OutboundMessage(UUID.randomUUID(), tenantId.value(), UUID.randomUUID(), UUID.randomUUID(),
                "+56912345678", MessageChannel.WHATSAPP, status, outcomeUnknown, SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", MessageOrigin.MANUAL, "Hola", 3,
                UUID.randomUUID(), attemptCount, sent || outcomeUnknown ? "wamid.seed" : null,
                status == MessageStatus.FAILED ? FailureCategory.UNKNOWN : null, sent ? NOW.minusSeconds(60) : null,
                status == MessageStatus.DELIVERED || status == MessageStatus.READ ? NOW.minusSeconds(60) : null,
                status == MessageStatus.READ ? NOW.minusSeconds(60) : null, UUID.randomUUID(), UUID.randomUUID(),
                NOW.minusSeconds(120), NOW.minusSeconds(60), 4);
    }

    private static DeliveryStatusReport report(String providerMessageId, DeliveryStatus status) {
        return new DeliveryStatusReport(providerMessageId, status, NOW.minusSeconds(30), Optional.empty());
    }

    private static void assertRefused(java.util.concurrent.Callable<?> call) {
        assertThatThrownBy(call::call).isInstanceOf(MessagingRefusedException.class)
                .extracting(e -> ((MessagingRefusedException) e).code()).isEqualTo(MessagingErrorCode.FORBIDDEN);
    }
}
