// ABOUTME: Exhaustive proof of AC-3: every (state, outcomeUnknown, command) cell matches ADR 0023 §2 T1 to T12.
// ABOUTME: Accepted cells are listed explicitly; every other cell must throw INVALID_TRANSITION.
package io.github.stevdrey.dokene.messaging.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class OutboundMessageTransitionMatrixTest {
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final UUID MEMBER = UUID.randomUUID();
    private static final int MAX_ATTEMPTS = 3;

    private enum Command {
        APPROVE, REJECT, CANCEL, REQUEST_SEND, START_ATTEMPT, COMPLETE_ACCEPTED, COMPLETE_REJECTED,
        COMPLETE_TRANSIENT, COMPLETE_UNKNOWN, RESOLVE_FAILED
    }

    private static final Map<Command, Function<OutboundMessage, Transition>> COMMANDS = Map.of(
            Command.APPROVE, m -> m.approve(MEMBER, LATER),
            Command.REJECT, m -> m.reject(MEMBER, LATER),
            Command.CANCEL, m -> m.cancel(MEMBER, LATER),
            Command.REQUEST_SEND, m -> m.requestSend(MEMBER, LATER),
            Command.START_ATTEMPT, m -> m.startAttempt(LATER),
            Command.COMPLETE_ACCEPTED, m -> m.completeAttempt(new ProviderSendResult.Accepted("wamid.1"), LATER,
                    MAX_ATTEMPTS),
            Command.COMPLETE_REJECTED, m -> m.completeAttempt(
                    new ProviderSendResult.RejectedPermanently(FailureCategory.INVALID_RECIPIENT), LATER, MAX_ATTEMPTS),
            Command.COMPLETE_TRANSIENT, m -> m.completeAttempt(
                    new ProviderSendResult.FailedTransiently(FailureCategory.RATE_LIMITED), LATER, MAX_ATTEMPTS),
            Command.COMPLETE_UNKNOWN, m -> m.completeAttempt(
                    new ProviderSendResult.OutcomeUnknown(Optional.of("wamid.2")), LATER, MAX_ATTEMPTS),
            Command.RESOLVE_FAILED, m -> m.resolveFailed(MEMBER, LATER));

    /** (state, outcomeUnknown) -> accepted commands and the status each lands on. */
    private static final Map<String, Map<Command, MessageStatus>> ACCEPTED = Map.of(
            key(MessageStatus.PENDING_APPROVAL, false), Map.of(
                    Command.APPROVE, MessageStatus.APPROVED,
                    Command.REJECT, MessageStatus.REJECTED,
                    Command.CANCEL, MessageStatus.CANCELLED),
            key(MessageStatus.APPROVED, false), Map.of(
                    Command.CANCEL, MessageStatus.CANCELLED,
                    Command.REQUEST_SEND, MessageStatus.QUEUED),
            key(MessageStatus.QUEUED, false), Map.of(Command.START_ATTEMPT, MessageStatus.SENDING),
            key(MessageStatus.SENDING, false), Map.of(
                    Command.COMPLETE_ACCEPTED, MessageStatus.SENT,
                    Command.COMPLETE_REJECTED, MessageStatus.FAILED,
                    Command.COMPLETE_TRANSIENT, MessageStatus.APPROVED,
                    Command.COMPLETE_UNKNOWN, MessageStatus.SENDING),
            key(MessageStatus.SENDING, true), Map.of(Command.RESOLVE_FAILED, MessageStatus.FAILED));

    @Test
    void everyStateFlagAndCommandCellMatchesTheAdrTable() {
        int cells = 0;
        for (MessageStatus state : MessageStatus.values()) {
            for (boolean unknown : new boolean[] {false, true}) {
                if (unknown && state != MessageStatus.SENDING) {
                    assertThatThrownBy(() -> message(state, true, 1))
                            .as("outcomeUnknown is representable only in SENDING")
                            .isInstanceOf(IllegalArgumentException.class);
                    continue;
                }
                Map<Command, MessageStatus> accepted = ACCEPTED.getOrDefault(key(state, unknown), Map.of());
                for (Command command : Command.values()) {
                    cells++;
                    OutboundMessage message = message(state, unknown, 1);
                    if (accepted.containsKey(command)) {
                        Transition transition = COMMANDS.get(command).apply(message);
                        assertThat(transition.next().status()).as("%s on %s/%s", command, state, unknown)
                                .isEqualTo(accepted.get(command));
                        assertThat(transition.next().version()).isEqualTo(message.version() + 1);
                        assertThat(transition.event().sequenceNumber()).isEqualTo(transition.next().version());
                        assertThat(transition.event().statusFrom()).isEqualTo(state);
                        assertThat(transition.event().statusTo()).isEqualTo(accepted.get(command));
                        assertThat(transition.event().applied()).isTrue();
                        assertThat(transition.next().updatedAt()).isEqualTo(LATER);
                        if (command != Command.COMPLETE_UNKNOWN) {
                            assertThat(transition.next().outcomeUnknown()).isFalse();
                        }
                    } else {
                        assertThatThrownBy(() -> COMMANDS.get(command).apply(message))
                                .as("%s on %s/%s must be refused", command, state, unknown)
                                .isInstanceOf(MessagingRefusedException.class)
                                .extracting(e -> ((MessagingRefusedException) e).code())
                                .isEqualTo(MessagingErrorCode.INVALID_TRANSITION);
                    }
                }
            }
        }
        assertThat(cells).isEqualTo(11 * Command.values().length);
    }

    @Test
    void transientFailureAtTheAttemptLimitLandsOnFailed() {
        OutboundMessage lastAttempt = message(MessageStatus.SENDING, false, MAX_ATTEMPTS);
        Transition transition = lastAttempt.completeAttempt(
                new ProviderSendResult.FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE), LATER, MAX_ATTEMPTS);
        assertThat(transition.next().status()).isEqualTo(MessageStatus.FAILED);
        assertThat(transition.next().failureCategory()).isEqualTo(FailureCategory.PROVIDER_UNAVAILABLE);
        assertThat(transition.event().type()).isEqualTo(MessageEventType.SEND_FAILED);
        assertThat(transition.event().attemptNumber()).isEqualTo(MAX_ATTEMPTS);

        OutboundMessage earlier = message(MessageStatus.SENDING, false, MAX_ATTEMPTS - 1);
        assertThat(earlier.completeAttempt(new ProviderSendResult.FailedTransiently(FailureCategory.RATE_LIMITED),
                LATER, MAX_ATTEMPTS).next().status()).isEqualTo(MessageStatus.APPROVED);
    }

    @Test
    void unknownOutcomeSetsTheFlagStoresTheIdAndBlocksFurtherProviderCalls() {
        Transition unknown = message(MessageStatus.SENDING, false, 1).completeAttempt(
                new ProviderSendResult.OutcomeUnknown(Optional.of("wamid.kept")), LATER, MAX_ATTEMPTS);
        assertThat(unknown.next().status()).isEqualTo(MessageStatus.SENDING);
        assertThat(unknown.next().outcomeUnknown()).isTrue();
        assertThat(unknown.next().providerMessageId()).isEqualTo("wamid.kept");
        assertThat(unknown.event().type()).isEqualTo(MessageEventType.SEND_OUTCOME_UNKNOWN);
        assertThatThrownBy(() -> unknown.next().completeAttempt(new ProviderSendResult.Accepted("wamid.3"), LATER,
                MAX_ATTEMPTS)).isInstanceOf(MessagingRefusedException.class);

        Transition resolved = unknown.next().resolveFailed(MEMBER, LATER);
        assertThat(resolved.next().status()).isEqualTo(MessageStatus.FAILED);
        assertThat(resolved.next().outcomeUnknown()).isFalse();
        assertThat(resolved.event().actorKind()).isEqualTo(MessageActorKind.MEMBER);
        assertThatThrownBy(() -> resolved.next().resolveFailed(MEMBER, LATER))
                .isInstanceOf(MessagingRefusedException.class);
    }

    @Test
    void submitStartsAtVersionOneWithTheFirstEvent() {
        Transition transition = OutboundMessage.submit(submission(), NOW);
        assertThat(transition.next().status()).isEqualTo(MessageStatus.PENDING_APPROVAL);
        assertThat(transition.next().version()).isEqualTo(1);
        assertThat(transition.next().attemptCount()).isZero();
        assertThat(transition.event().type()).isEqualTo(MessageEventType.SUBMITTED);
        assertThat(transition.event().statusFrom()).isNull();
        assertThat(transition.event().sequenceNumber()).isEqualTo(1);
        assertThat(transition.event().membershipId()).isEqualTo(MEMBER);
    }

    @Test
    void startAttemptIncrementsTheCounterAndSentStoresTheProviderId() {
        Transition started = message(MessageStatus.QUEUED, false, 0).startAttempt(LATER);
        assertThat(started.next().attemptCount()).isEqualTo(1);
        assertThat(started.event().actorKind()).isEqualTo(MessageActorKind.SYSTEM);
        assertThat(started.event().attemptNumber()).isEqualTo(1);
        Transition sent = started.next().completeAttempt(new ProviderSendResult.Accepted("wamid.ok"), LATER,
                MAX_ATTEMPTS);
        assertThat(sent.next().providerMessageId()).isEqualTo("wamid.ok");
        assertThat(sent.next().sentAt()).isEqualTo(LATER);
        assertThat(sent.event().type()).isEqualTo(MessageEventType.SENT);
    }

    @Test
    void versionOverflowIsReportedAsStaleVersion() {
        OutboundMessage atLimit = new OutboundMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "+56912345678", MessageChannel.WHATSAPP, MessageStatus.PENDING_APPROVAL, false,
                SemanticAction.GENERAL_CHECK_IN, SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419",
                MessageOrigin.MANUAL, "Hola", 0, UUID.randomUUID(), 0, null, null, null, null, null, MEMBER,
                UUID.randomUUID(), NOW, NOW, Long.MAX_VALUE);
        assertThatThrownBy(() -> atLimit.approve(MEMBER, LATER))
                .isInstanceOf(MessagingRefusedException.class)
                .extracting(e -> ((MessagingRefusedException) e).code())
                .isEqualTo(MessagingErrorCode.STALE_VERSION);
    }

    static OutboundMessage message(MessageStatus status, boolean outcomeUnknown, int attemptCount) {
        boolean sent = Set.of(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ).contains(status);
        boolean hasProviderId = sent || outcomeUnknown;
        return new OutboundMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "+56912345678", MessageChannel.WHATSAPP, status, outcomeUnknown, SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", MessageOrigin.AI_DRAFT, "Hola, ¿cómo estás?",
                3, UUID.randomUUID(), attemptCount, hasProviderId ? "wamid.seed" : null,
                status == MessageStatus.FAILED ? FailureCategory.UNKNOWN : null, sent ? NOW : null,
                status == MessageStatus.DELIVERED || status == MessageStatus.READ ? NOW : null,
                status == MessageStatus.READ ? NOW : null, MEMBER, UUID.randomUUID(), NOW, NOW, 4);
    }

    static OutboundMessage.Submission submission() {
        return new OutboundMessage.Submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "+56912345678", SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", MessageOrigin.MANUAL, "Hola", 2,
                UUID.randomUUID(), MEMBER, UUID.randomUUID());
    }

    private static String key(MessageStatus status, boolean unknown) {
        return status + "/" + unknown;
    }

    static List<MessageStatus> all() {
        return List.of(MessageStatus.values());
    }
}
