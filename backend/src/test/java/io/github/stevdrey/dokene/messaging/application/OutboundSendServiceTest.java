// ABOUTME: Unit tests for the three phase send with fake ports: policy order, replay dominance, outcomes, stale phase 3.
// ABOUTME: A no op transaction manager runs the TransactionTemplate callbacks inline (spec 0003 AC-2 to AC-5, AC-11).
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.FollowUpTouchRecorder;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageOperation;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.messaging.domain.SendAttemptOutcome;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OutboundSendServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final String BODY = "Hola, ¿cómo le ha ido con su compra?";

    private final OutboundMessageRepository messages = mock(OutboundMessageRepository.class);
    private final MessageIdempotencyStore idempotency = mock(MessageIdempotencyStore.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final OutboundMessageCommandService guards = mock(OutboundMessageCommandService.class);
    private final TenantAuthorizationService authorization = mock(TenantAuthorizationService.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final MessageAuditPort audit = mock(MessageAuditPort.class);
    private final FollowUpTouchRecorder touches = mock(FollowUpTouchRecorder.class);
    private final OutboundGate gate = mock(OutboundGate.class);
    private final OutboundSendRateLimiter rateLimiter = mock(OutboundSendRateLimiter.class);
    private final MessagingProvider provider = mock(MessagingProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MessagingProvider> providers = mock(ObjectProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PlatformTransactionManager transactions = new NoOpTransactionManager();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final CustomerPhone phone = CustomerPhone.create("+50688880055", true);
    private final TenantContext context = new TenantContext(new TenantId(tenantId), new IdentityId(UUID.randomUUID()),
            new TenantMembershipId(UUID.randomUUID()), TenantRole.OPERATOR, TenantMembershipStatus.ACTIVE);
    private final AtomicReference<OutboundMessage> stored = new AtomicReference<>();
    private final List<SendAttempt> attempts = new ArrayList<>();

    private OutboundSendService service;

    @BeforeEach
    void setUp() {
        service = service(3);
        when(contexts.requireCurrent()).thenReturn(context);
        when(rateLimiter.acquire(tenantId)).thenReturn(Optional.empty());
        when(providers.getIfAvailable()).thenReturn(provider);
        when(gate.check()).thenReturn(OutboundGateStatus.ENABLED);
        stored.set(approved());
        when(guards.lockMessage(eq(context), any(), anyLong())).thenAnswer(invocation -> stored.get());
        when(messages.findApprovalByMessage(eq(tenantId), any())).thenAnswer(invocation -> Optional.of(
                new MessageApproval(UUID.randomUUID(), tenantId, invocation.getArgument(1), ApprovalDecision.APPROVE,
                        null, NOW, context.membershipId().value(), context.identityId().value())));
        Customer customer = Customer.create(new CustomerId(customerId), new TenantId(tenantId), "Cliente", null,
                List.of(phone), NOW);
        when(customers.findById(new TenantId(tenantId), new CustomerId(customerId))).thenReturn(Optional.of(customer));
        when(customers.findByIdForUpdate(new TenantId(tenantId), new CustomerId(customerId)))
                .thenReturn(Optional.of(customer));
        when(guards.requireEligible(customer)).thenReturn(snapshot(null));
        when(guards.requireMapped(SemanticTemplateIntent.GENERAL_FOLLOW_UP))
                .thenReturn(new ResolvedTemplate("general_follow_up", "es-419", List.of("body")));
        when(messages.findOpenByCustomer(eq(tenantId), eq(customerId), any())).thenReturn(Optional.empty());
        when(messages.findLatestSentAt(tenantId, customerId)).thenReturn(Optional.empty());
        when(messages.update(any(), any())).thenAnswer(invocation -> {
            OutboundMessage next = invocation.getArgument(1);
            stored.set(next);
            return next;
        });
        when(messages.insertAttempt(any())).thenAnswer(invocation -> {
            SendAttempt attempt = invocation.getArgument(0);
            attempts.add(attempt);
            return attempt;
        });
        when(messages.updateAttempt(any())).thenAnswer(invocation -> {
            SendAttempt attempt = invocation.getArgument(0);
            attempts.replaceAll(row -> row.id().equals(attempt.id()) ? attempt : row);
            return attempt;
        });
        when(messages.findAttempts(eq(tenantId), any())).thenAnswer(invocation -> List.copyOf(attempts));
        when(messages.findByIdForUpdate(eq(tenantId), any())).thenAnswer(invocation -> Optional.of(stored.get()));
        when(messages.findById(eq(tenantId), any())).thenAnswer(invocation -> Optional.of(stored.get()));
    }

    @Test
    void sendsInThreePhasesCommittingTheAttemptBeforeTheProviderAndTouchingTheCadenceOnSent() {
        when(provider.send(any())).thenReturn(new ProviderSendResult.Accepted("local-1"));

        CommandResult result = service.send(command("k1"));

        assertThat(result.created()).isTrue();
        assertThat(result.message().status()).isEqualTo(MessageStatus.SENT);
        assertThat(result.message().providerMessageId()).isEqualTo("local-1");
        assertThat(result.message().sentAt()).isEqualTo(NOW);
        assertThat(result.message().version()).isEqualTo(5);
        assertThat(attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.outcome()).isEqualTo(SendAttemptOutcome.ACCEPTED);
            assertThat(attempt.attemptNumber()).isEqualTo(1);
            assertThat(attempt.sendKey()).isEqualTo(result.message().sendKey());
        });
        var order = inOrder(messages, idempotency, audit, provider, touches, events);
        order.verify(messages).insertAttempt(any());
        order.verify(idempotency).record(any());
        order.verify(audit).sendRequested(any());
        order.verify(provider).send(any());
        order.verify(touches).recordOutboundMessage(new CustomerId(customerId), NOW);
        order.verify(audit).sent(any());
        order.verify(events).publishEvent(new SendAttemptCompleted(tenantId, result.message().id(),
                Optional.of("local-1"), SendAttemptOutcome.ACCEPTED));
        verify(provider).send(new OutboundSendCommand(phone.e164(), "general_follow_up", "es-419", List.of(BODY),
                "es-419", result.message().sendKey(), tenantId));
        assertThat(registry.counter(OutboundSendService.ATTEMPTS, "outcome", "ACCEPTED").count()).isEqualTo(1.0);
        assertThat(registry.timer(OutboundSendService.DURATION).count()).isEqualTo(1);
    }

    @Test
    void aReplayWinsBeforeTheRateLimitAndEverySwitchAndCallsNoProvider() {
        OutboundMessage sent = stored.get();
        UUID attemptId = UUID.randomUUID();
        String fingerprint = io.github.stevdrey.dokene.messaging.domain.RequestFingerprint.of(MessageOperation.SEND,
                List.of(sent.id().toString(), "2"));
        when(idempotency.find(tenantId, MessageOperation.SEND, "k1")).thenReturn(Optional.of(
                new MessageIdempotencyStore.IdempotencyRecord(UUID.randomUUID(), tenantId, MessageOperation.SEND,
                        "k1", fingerprint, sent.id(), attemptId, NOW)));
        when(rateLimiter.acquire(tenantId)).thenReturn(Optional.of(Duration.ofSeconds(5)));
        when(gate.check()).thenReturn(OutboundGateStatus.OUTBOUND_DISABLED_FOR_TENANT);

        CommandResult result = service.send(command("k1"));

        assertThat(result.created()).isFalse();
        assertThat(result.message()).isEqualTo(sent);
        verifyNoInteractions(provider, rateLimiter, gate, touches);
        verify(messages, never()).update(any(), any());

        assertThatThrownBy(() -> service.send(new SendMessageCommand(sent.id(), "3", "k1")))
                .isInstanceOf(MessagingRefusedException.class)
                .extracting(e -> ((MessagingRefusedException) e).code())
                .isEqualTo(MessagingErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void refusesInPolicyOrderBeforeTakingAnyLock() {
        when(rateLimiter.acquire(tenantId)).thenReturn(Optional.of(Duration.ofSeconds(17)));
        MessagingRefusedException limited = refusal("k1");
        assertThat(limited.code()).isEqualTo(MessagingErrorCode.RATE_LIMITED);
        assertThat(limited.retryAfter()).contains(Duration.ofSeconds(17));
        when(rateLimiter.acquire(tenantId)).thenReturn(Optional.empty());

        OutboundSendService disabled = service(3, false);
        assertThatThrownBy(() -> disabled.send(command("k2"))).isInstanceOf(MessagingRefusedException.class)
                .extracting(e -> ((MessagingRefusedException) e).code())
                .isEqualTo(MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY);

        when(providers.getIfAvailable()).thenReturn(null);
        assertThat(refusal("k3").code()).isEqualTo(MessagingErrorCode.PROVIDER_UNAVAILABLE);
        when(providers.getIfAvailable()).thenReturn(provider);

        when(gate.check()).thenReturn(OutboundGateStatus.OUTBOUND_DISABLED_FOR_TENANT);
        assertThat(refusal("k4").code()).isEqualTo(MessagingErrorCode.OUTBOUND_DISABLED_FOR_TENANT);
        when(gate.check()).thenReturn(OutboundGateStatus.INTEGRATION_DISABLED);
        assertThat(refusal("k5").code()).isEqualTo(MessagingErrorCode.INTEGRATION_DISABLED);

        verify(guards, never()).lockMessage(any(), any(), anyLong());
        verify(messages, never()).update(any(), any());
        verifyNoInteractions(provider, audit);
    }

    @Test
    void refusesANonApprovedMessageAMissingApproveRowAndAnOpenSibling() {
        stored.set(approved().cancel(context.membershipId().value(), NOW).next());
        assertThat(refusal("k1").code()).isEqualTo(MessagingErrorCode.INVALID_TRANSITION);

        stored.set(approved());
        when(messages.findApprovalByMessage(tenantId, stored.get().id())).thenReturn(Optional.empty());
        assertThat(refusal("k2").code()).isEqualTo(MessagingErrorCode.INVALID_TRANSITION);

        when(messages.findApprovalByMessage(eq(tenantId), any())).thenAnswer(invocation -> Optional.of(
                new MessageApproval(UUID.randomUUID(), tenantId, invocation.getArgument(1), ApprovalDecision.APPROVE,
                        null, NOW, context.membershipId().value(), context.identityId().value())));
        when(messages.findOpenByCustomer(eq(tenantId), eq(customerId), any())).thenReturn(Optional.of(stored.get()));
        assertThat(refusal("k3").code()).isEqualTo(MessagingErrorCode.MESSAGE_ALREADY_OPEN);
        verifyNoInteractions(provider);
    }

    @Test
    void refusesWhenAMessageWasSentStrictlyAfterTheAnchorDateButNotOnIt() {
        LocalDate anchor = LocalDate.of(2026, 10, 9);
        when(guards.requireEligible(any())).thenReturn(snapshot(anchor));
        when(messages.findLatestSentAt(tenantId, customerId))
                .thenReturn(Optional.of(Instant.parse("2026-10-09T23:59:59Z")));
        when(provider.send(any())).thenReturn(new ProviderSendResult.Accepted("local-1"));
        assertThat(service.send(command("k1")).message().status()).isEqualTo(MessageStatus.SENT);

        stored.set(approved());
        when(messages.findLatestSentAt(tenantId, customerId))
                .thenReturn(Optional.of(Instant.parse("2026-10-10T00:00:00Z")));
        assertThat(refusal("k2").code()).isEqualTo(MessagingErrorCode.CONTACT_FREQUENCY_EXCEEDED);
    }

    @Test
    void refusesWhenTheAttemptCountReachedTheMaximum() {
        when(provider.send(any())).thenReturn(new ProviderSendResult.FailedTransiently(FailureCategory.RATE_LIMITED));
        CommandResult first = service.send(command("k1"));
        assertThat(first.message().status()).isEqualTo(MessageStatus.APPROVED);
        assertThat(first.message().attemptCount()).isEqualTo(1);
        verify(audit).sendFailed(any());

        OutboundSendService lowered = service(1);
        assertThatThrownBy(() -> lowered.send(command("k2"))).isInstanceOf(MessagingRefusedException.class)
                .extracting(e -> ((MessagingRefusedException) e).code())
                .isEqualTo(MessagingErrorCode.SEND_ATTEMPTS_EXHAUSTED);

        CommandResult second = service.send(command("k3"));
        assertThat(second.message().attemptCount()).isEqualTo(2);
        assertThat(attempts).hasSize(2);
    }

    @Test
    void anythingTheAdapterThrowsIsAnUnknownOutcomeWithNoId() {
        when(provider.send(any())).thenThrow(new IllegalStateException("socket"));

        CommandResult result = service.send(command("k1"));

        assertThat(result.message().status()).isEqualTo(MessageStatus.SENDING);
        assertThat(result.message().outcomeUnknown()).isTrue();
        assertThat(result.message().providerMessageId()).isNull();
        assertThat(attempts).singleElement().extracting(SendAttempt::outcome)
                .isEqualTo(SendAttemptOutcome.OUTCOME_UNKNOWN);
        verify(audit).sendOutcomeUnknown(any());
        verify(touches, never()).recordOutboundMessage(any(), any());
        assertThat(registry.counter(OutboundSendService.ATTEMPTS, "outcome", "OUTCOME_UNKNOWN").count())
                .isEqualTo(1.0);

        assertThat(refusal("k2").code()).isEqualTo(MessagingErrorCode.INVALID_TRANSITION);
    }

    @Test
    void rejectedPermanentlyLandsFailedWithTheCategory() {
        when(provider.send(any()))
                .thenReturn(new ProviderSendResult.RejectedPermanently(FailureCategory.INVALID_RECIPIENT));

        CommandResult result = service.send(command("k1"));

        assertThat(result.message().status()).isEqualTo(MessageStatus.FAILED);
        assertThat(result.message().failureCategory()).isEqualTo(FailureCategory.INVALID_RECIPIENT);
        assertThat(attempts.getFirst().failureCategory()).isEqualTo(FailureCategory.INVALID_RECIPIENT);
        verify(audit).sendFailed(any());
    }

    @Test
    void phaseThreeWritesNothingWhenTheMessageMovedMeanwhile() {
        when(provider.send(any())).thenAnswer(invocation -> {
            // Someone else completed the attempt while the provider was called.
            OutboundMessage moved = stored.get().completeAttempt(
                    new ProviderSendResult.OutcomeUnknown(Optional.empty()), NOW, 3).next();
            stored.set(moved);
            return new ProviderSendResult.Accepted("local-1");
        });

        CommandResult result = service.send(command("k1"));

        assertThat(result.message().status()).isEqualTo(MessageStatus.SENDING);
        assertThat(result.message().outcomeUnknown()).isTrue();
        verify(messages, org.mockito.Mockito.times(2)).update(any(), any());
        verify(messages, never()).updateAttempt(any());
        verifyNoInteractions(touches, events);
    }

    @Test
    void aFailingPhaseThreePropagatesAndCountsTheFailure() {
        when(provider.send(any())).thenReturn(new ProviderSendResult.Accepted("local-1"));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit down")).when(audit).sent(any());

        assertThatThrownBy(() -> service.send(command("k1"))).isInstanceOf(IllegalStateException.class);

        assertThat(registry.counter(OutboundSendService.ATTEMPTS, "outcome",
                OutboundSendService.COMPLETION_FAILED).count()).isEqualTo(1.0);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void validatesTheHeadersBeforeAnythingElse() {
        assertThatThrownBy(() -> service.send(new SendMessageCommand(null, "2", "k")))
                .isInstanceOf(MessagingRefusedException.class);
        assertThatThrownBy(() -> service.send(new SendMessageCommand(UUID.randomUUID(), "x", "k")))
                .isInstanceOf(MessagingRefusedException.class);
        assertThatThrownBy(() -> service.send(new SendMessageCommand(UUID.randomUUID(), "2", "bad key!")))
                .isInstanceOf(MessagingRefusedException.class);
        verifyNoInteractions(contexts, idempotency);
    }

    private MessagingRefusedException refusal(String key) {
        try {
            service.send(command(key));
        } catch (MessagingRefusedException refused) {
            return refused;
        }
        throw new AssertionError("expected a refusal");
    }

    private SendMessageCommand command(String key) {
        return new SendMessageCommand(stored.get().id(), Long.toString(stored.get().version()), key);
    }

    private OutboundSendService service(int maxAttempts) {
        return service(maxAttempts, true);
    }

    @SuppressWarnings("unchecked")
    private OutboundSendService service(int maxAttempts, boolean enabled) {
        var properties = new OutboundMessagingProperties(new OutboundMessagingProperties.Outbound(enabled),
                maxAttempts, null, null);
        ObjectProvider<MeterRegistry> registries = mock(ObjectProvider.class);
        when(registries.getIfAvailable()).thenReturn(registry);
        return new OutboundSendService(messages, idempotency, customers, guards, authorization, contexts, audit,
                touches, gate, rateLimiter, providers, properties, transactions, events, registries,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OutboundMessage approved() {
        OutboundMessage submitted = OutboundMessage.submit(new OutboundMessage.Submission(UUID.randomUUID(),
                tenantId, customerId, phone.id(), phone.e164(), SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", MessageOrigin.MANUAL, BODY, 1L,
                UUID.randomUUID(), context.membershipId().value(), context.identityId().value()), NOW).next();
        return submitted.approve(context.membershipId().value(), NOW).next();
    }

    private FollowUpService.FollowUpEvaluationSnapshot snapshot(LocalDate anchorDate) {
        LocalDate today = NOW.atZone(ZONE).toLocalDate();
        return new FollowUpService.FollowUpEvaluationSnapshot(new FollowUpEvaluation(new CustomerId(customerId),
                FollowUpStatus.DUE, List.of(FollowUpReason.DUE_TODAY), NOW, today, ZONE, today,
                FollowUpTimingSource.LAST_PURCHASE, 30, NOW, anchorDate), 1L);
    }

    /** Runs each TransactionTemplate callback inline; commit and rollback are no ops. */
    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
