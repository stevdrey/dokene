// ABOUTME: Unit tests for the MessageDetail read model: lastAttempt picks the highest attempt number.
// ABOUTME: Covers the ADR 0023 §4.4 reading rule the detail representation relies on (spec 0002 AC-1).
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageChannel;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.messaging.domain.SendAttemptOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageDetailTest {
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MESSAGE_ID = UUID.randomUUID();

    @Test
    void lastAttemptIsTheHighestNumberRegardlessOfListOrder() {
        SendAttempt first = attempt(1, SendAttemptOutcome.FAILED_TRANSIENT);
        SendAttempt third = attempt(3, SendAttemptOutcome.ACCEPTED);
        SendAttempt second = attempt(2, SendAttemptOutcome.FAILED_TRANSIENT);

        MessageDetail detail = detail(List.of(third, first, second));

        assertThat(detail.lastAttempt()).contains(third);
    }

    @Test
    void lastAttemptIsEmptyWithoutAttempts() {
        assertThat(detail(List.of()).lastAttempt()).isEmpty();
    }

    @Test
    void copiesAttemptsSoTheCallerCannotChangeThemAfterwards() {
        List<SendAttempt> attempts = new ArrayList<>(List.of(attempt(1, SendAttemptOutcome.STARTED)));

        MessageDetail detail = detail(attempts);
        attempts.clear();

        assertThat(detail.attempts()).hasSize(1);
        assertThatThrownBy(() -> detail.attempts().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void refusesNullParts() {
        OutboundMessage message = message();
        assertThatThrownBy(() -> new MessageDetail(null, "Cliente", Optional.empty(), Optional.empty(), List.of(), false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MessageDetail(message, null, Optional.empty(), Optional.empty(), List.of(), false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MessageDetail(message, "Cliente", null, Optional.empty(), List.of(), false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MessageDetail(message, "Cliente", Optional.empty(), null, List.of(), false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MessageDetail(message, "Cliente", Optional.empty(), Optional.empty(), null, false))
                .isInstanceOf(NullPointerException.class);
    }

    private static MessageDetail detail(List<SendAttempt> attempts) {
        return new MessageDetail(message(), "Cliente", Optional.empty(), Optional.empty(), attempts, false);
    }

    private static SendAttempt attempt(int number, SendAttemptOutcome outcome) {
        boolean failed = outcome == SendAttemptOutcome.FAILED_TRANSIENT || outcome == SendAttemptOutcome.FAILED_PERMANENT;
        boolean started = outcome == SendAttemptOutcome.STARTED;
        return new SendAttempt(UUID.randomUUID(), TENANT_ID, MESSAGE_ID, number, UUID.randomUUID(), outcome,
                failed ? FailureCategory.PROVIDER_UNAVAILABLE : null,
                outcome == SendAttemptOutcome.ACCEPTED ? "wamid." + number : null, NOW,
                started ? null : NOW.plusSeconds(number), UUID.randomUUID(), UUID.randomUUID());
    }

    static OutboundMessage message() {
        return new OutboundMessage(MESSAGE_ID, TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), "+50688887777",
                MessageChannel.WHATSAPP, MessageStatus.PENDING_APPROVAL, false, SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", MessageOrigin.MANUAL, "Hola", 7,
                UUID.randomUUID(), 0, null, null, null, null, null, UUID.randomUUID(), UUID.randomUUID(), NOW, NOW, 1);
    }
}
