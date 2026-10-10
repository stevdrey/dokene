// ABOUTME: Unit tests for the messaging value objects' invariants: OutboundMessage, MessageEvent, SendAttempt,
// ABOUTME: DeliveryStatusReport and MessagingLocales. Covers the construction rules behind AC-3 and AC-10 of spec 0001.
package io.github.stevdrey.dokene.messaging.domain;

import static io.github.stevdrey.dokene.messaging.domain.OutboundMessageTransitionMatrixTest.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboundMessageInvariantsTest {
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final UUID MEMBER = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(strings = {"56912345678", "+0912345678", "+5691234", "+56 912345678", "+569123456789012345"})
    void rejectsRecipientPhonesThatAreNotE164(String phone) {
        assertThatThrownBy(() -> withPhoneAndBody(phone, "Hola")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBodiesUpToOneThousandCodePointsAndRejectsBlankOrLonger() {
        String emoji = "😀";
        assertThatCode(() -> withPhoneAndBody("+56912345678", emoji.repeat(1000))).doesNotThrowAnyException();
        assertThatThrownBy(() -> withPhoneAndBody("+56912345678", emoji.repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withPhoneAndBody("+56912345678", "   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedLocalesNegativeCountersAndInconsistentFlags() {
        OutboundMessage base = message(MessageStatus.APPROVED, false, 0);
        assertThatThrownBy(() -> copy(base, "en-US", 0, false, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(base, "es-419", -1, false, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(base, "es-419", 0, true, null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SENDING");
        assertThatThrownBy(() -> copy(base, "es-419", 0, false, FailureCategory.UNKNOWN, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FAILED");
        assertThatThrownBy(() -> copy(base, "es-419", 0, false, null, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(base, "es-419", 0, false, null, "x".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> copy(base, "es-419", 0, false, null, "wamid.ok")).doesNotThrowAnyException();
    }

    @Test
    void openAndTerminalAreComplementsOverEveryStatus() {
        for (MessageStatus status : MessageStatus.values()) {
            OutboundMessage m = message(status, false, 0);
            assertThat(m.isOpen()).as(status.name()).isEqualTo(!m.isTerminal());
            assertThat(m.isTerminal()).as(status.name()).isEqualTo(status.terminal());
        }
        for (MessageStatus status : MessageStatus.values()) {
            assertThat(status.open()).as(status.name()).isEqualTo(!status.terminal());
        }
        assertThat(MessageStatus.values()).filteredOn(MessageStatus::terminal)
                .containsExactlyInAnyOrder(MessageStatus.READ, MessageStatus.REJECTED, MessageStatus.CANCELLED,
                        MessageStatus.FAILED);
    }

    @Test
    void resolveFailedOnlyAppliesToAnUnknownOutcomeAndRecordsAMemberEvent() {
        OutboundMessage unknown = message(MessageStatus.SENDING, true, 2);

        Transition resolved = unknown.resolveFailed(MEMBER, NOW.plusSeconds(5));

        assertThat(resolved.next().status()).isEqualTo(MessageStatus.FAILED);
        assertThat(resolved.next().failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(resolved.next().outcomeUnknown()).isFalse();
        assertThat(resolved.next().version()).isEqualTo(unknown.version() + 1);
        assertThat(resolved.event().actorKind()).isEqualTo(MessageActorKind.MEMBER);
        assertThat(resolved.event().membershipId()).isEqualTo(MEMBER);
        assertThat(resolved.event().attemptNumber()).isEqualTo(2);
        assertThat(resolved.event().sequenceNumber()).isEqualTo(resolved.next().version());
        assertThatThrownBy(() -> message(MessageStatus.SENDING, false, 1).resolveFailed(MEMBER, NOW))
                .isInstanceOf(MessagingRefusedException.class)
                .extracting(e -> ((MessagingRefusedException) e).code()).isEqualTo(MessagingErrorCode.INVALID_TRANSITION);
        assertThatThrownBy(() -> unknown.resolveFailed(null, NOW)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void messageEventRejectsInconsistentShapes() {
        UUID tenant = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        assertThatThrownBy(() -> new MessageEvent(UUID.randomUUID(), tenant, message, 0, MessageEventType.APPROVED,
                MessageStatus.PENDING_APPROVAL, MessageStatus.APPROVED, true, MessageActorKind.MEMBER, MEMBER, null, null, NOW))
                .as("sequence starts at 1").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageEvent(UUID.randomUUID(), tenant, message, 1, MessageEventType.SUBMITTED,
                MessageStatus.APPROVED, MessageStatus.PENDING_APPROVAL, true, MessageActorKind.MEMBER, MEMBER, null, null, NOW))
                .as("submitted has no source").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageEvent(UUID.randomUUID(), tenant, message, 2, MessageEventType.APPROVED,
                null, MessageStatus.APPROVED, true, MessageActorKind.MEMBER, MEMBER, null, null, NOW))
                .as("only submitted lacks a source").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageEvent(UUID.randomUUID(), tenant, message, 2, MessageEventType.DELIVERY_UPDATED,
                MessageStatus.SENT, MessageStatus.DELIVERED, false, MessageActorKind.SYSTEM, null, null, null, NOW))
                .as("non applied keeps the status").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageEvent(UUID.randomUUID(), tenant, message, 2, MessageEventType.APPROVED,
                MessageStatus.PENDING_APPROVAL, MessageStatus.APPROVED, true, MessageActorKind.MEMBER, null, null, null, NOW))
                .as("member needs a membership").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageEvent(UUID.randomUUID(), tenant, message, 2, MessageEventType.SENT,
                MessageStatus.SENDING, MessageStatus.SENT, true, MessageActorKind.SYSTEM, MEMBER, null, null, NOW))
                .as("system carries no membership").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageEvent(UUID.randomUUID(), tenant, message, 2, MessageEventType.SENT,
                MessageStatus.SENDING, MessageStatus.SENT, true, MessageActorKind.SYSTEM, null, null, 0, NOW))
                .as("attempt number starts at 1").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendAttemptCompletesOnceAndMapsEveryProviderAnswer() {
        OutboundMessage sending = message(MessageStatus.SENDING, false, 1);
        SendAttempt started = SendAttempt.started(sending, MEMBER, UUID.randomUUID(), NOW);
        assertThat(started.outcome()).isEqualTo(SendAttemptOutcome.STARTED);
        assertThat(started.attemptNumber()).isEqualTo(1);
        assertThat(started.sendKey()).isEqualTo(sending.sendKey());
        assertThat(started.finishedAt()).isNull();

        SendAttempt accepted = started.completed(new ProviderSendResult.Accepted("wamid.9"), NOW.plusSeconds(1));
        assertThat(accepted.outcome()).isEqualTo(SendAttemptOutcome.ACCEPTED);
        assertThat(accepted.providerMessageId()).isEqualTo("wamid.9");
        assertThat(accepted.finishedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(started.completed(new ProviderSendResult.RejectedPermanently(FailureCategory.INVALID_RECIPIENT), NOW))
                .extracting(SendAttempt::outcome, SendAttempt::failureCategory)
                .containsExactly(SendAttemptOutcome.FAILED_PERMANENT, FailureCategory.INVALID_RECIPIENT);
        assertThat(started.completed(new ProviderSendResult.FailedTransiently(FailureCategory.RATE_LIMITED), NOW)
                .outcome()).isEqualTo(SendAttemptOutcome.FAILED_TRANSIENT);
        SendAttempt unknown = started.completed(new ProviderSendResult.OutcomeUnknown(Optional.empty()), NOW);
        assertThat(unknown.outcome()).isEqualTo(SendAttemptOutcome.OUTCOME_UNKNOWN);
        assertThat(unknown.providerMessageId()).isNull();
        assertThatThrownBy(() -> accepted.completed(new ProviderSendResult.Accepted("wamid.10"), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deliveryReportRequiresAUsableProviderIdAndDefaultsTheFailureToUnknown() {
        DeliveryStatusReport failed = new DeliveryStatusReport("wamid.1", DeliveryStatus.FAILED, NOW, Optional.empty());
        assertThat(failed.failureOrUnknown()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(new DeliveryStatusReport("wamid.1", DeliveryStatus.FAILED, NOW,
                Optional.of(FailureCategory.POLICY_VIOLATION)).failureOrUnknown()).isEqualTo(FailureCategory.POLICY_VIOLATION);
        assertThatThrownBy(() -> new DeliveryStatusReport(" ", DeliveryStatus.SENT, NOW, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryStatusReport("x".repeat(129), DeliveryStatus.SENT, NOW, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryStatusReport("wamid.1", null, NOW, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeliveryStatusReport("wamid.1", DeliveryStatus.SENT, NOW, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void onlyLatinAmericanSpanishIsSupportedForNow() {
        assertThat(MessagingLocales.supported("es-419")).isTrue();
        assertThat(MessagingLocales.supported("es-CL")).isFalse();
        assertThat(MessagingLocales.supported("ES-419")).isFalse();
        assertThat(MessagingLocales.supported(null)).isFalse();
    }

    private static OutboundMessage withPhoneAndBody(String phone, String body) {
        return new OutboundMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), phone,
                MessageChannel.WHATSAPP, MessageStatus.PENDING_APPROVAL, false, SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", MessageOrigin.MANUAL, body, 1, UUID.randomUUID(),
                0, null, null, null, null, null, MEMBER, UUID.randomUUID(), NOW, NOW, 1);
    }

    private static OutboundMessage copy(OutboundMessage m, String locale, int attempts, boolean unknown,
            FailureCategory category, String providerId) {
        return new OutboundMessage(m.id(), m.tenantId(), m.customerId(), m.contactId(), m.recipientPhone(), m.channel(),
                m.status(), unknown, m.action(), m.templateIntent(), locale, m.origin(), m.body(),
                m.sourcePolicyVersion(), m.sendKey(), attempts, providerId, category, m.sentAt(), m.deliveredAt(),
                m.readAt(), m.createdByMembershipId(), m.createdByActorId(), m.createdAt(), m.updatedAt(), m.version());
    }
}
