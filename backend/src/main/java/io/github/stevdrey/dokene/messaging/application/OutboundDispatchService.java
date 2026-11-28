// ABOUTME: Manual dispatch (issue 138): claim (T6, T7, the attempt row) in one transaction, call the provider outside
// ABOUTME: any transaction, then record the outcome (T8 to T11) in a second one. Never resends on its own.
// ABOUTME: The provider health gate (ADR 0038) is the last policy step of the claim and may refuse or probe first.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpTouchRecorder;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthGate.Admission;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthGate.Pass;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageOperation;
import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind;
import io.github.stevdrey.dokene.messaging.domain.RequestFingerprint;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.messaging.domain.Transition;
import io.github.stevdrey.dokene.tenant.application.CurrentMembershipAuthorizer;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The committed STARTED attempt row is the durable dispatch intent and claim: it names the authorizing member, and the
 * message stays SENDING until the outcome is recorded, so no second send can start (ADR 0023 §4.2, ADR 0028). A crash
 * or an outcome that cannot be recorded leaves that claim in place: sending for the message halts rather than risk a
 * duplicate, and reconciliation turns it into an unknown outcome for a human to resolve (T12).
 */
@Service
public class OutboundDispatchService {
    private static final Logger log = LoggerFactory.getLogger(OutboundDispatchService.class);

    private final OutboundMessageRepository messages;
    private final MessageIdempotencyStore idempotency;
    private final CustomerRepository customers;
    private final SendPolicyGate gate;
    private final MessagingProvider provider;
    private final FollowUpTouchRecorder touches;
    private final MessageCommandSupport support;
    private final CurrentMembershipAuthorizer members;
    private final TenantContextProvider contexts;
    private final MessageAuditPort audit;
    private final ProviderHealthRecorder health;
    private final ProviderHealthGate healthGate;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OutboundDispatchService(OutboundMessageRepository messages, MessageIdempotencyStore idempotency,
            CustomerRepository customers, SendPolicyGate gate, MessagingProvider provider,
            FollowUpTouchRecorder touches, MessageCommandSupport support, CurrentMembershipAuthorizer members,
            TenantContextProvider contexts, MessageAuditPort audit, ProviderHealthRecorder health,
            ProviderHealthGate healthGate, PlatformTransactionManager transactionManager, Clock clock) {
        this.messages = Objects.requireNonNull(messages);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.customers = Objects.requireNonNull(customers);
        this.gate = Objects.requireNonNull(gate);
        this.provider = Objects.requireNonNull(provider);
        this.touches = Objects.requireNonNull(touches);
        this.support = Objects.requireNonNull(support);
        this.members = Objects.requireNonNull(members);
        this.contexts = Objects.requireNonNull(contexts);
        this.audit = Objects.requireNonNull(audit);
        this.health = Objects.requireNonNull(health);
        this.healthGate = Objects.requireNonNull(healthGate);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * T6 to T11 for one operator request. A deterministic refusal happens before anything is written and before any
     * provider call. Once the attempt row is committed, every provider answer is a {@code created} result carrying
     * the message's new state (ADR 0023 §4.4); a replay of the same key returns the current state instead.
     *
     * <p>When the provider's health is unknown or its cool down is over, the first pass through the policy writes
     * nothing and rolls back, the read only probe runs with no lock held, and a passed probe lets this same request
     * run the whole policy once more (ADR 0038). A request never probes twice.
     */
    public CommandResult send(SendMessageCommand command) {
        Objects.requireNonNull(command, "Command is required");
        if (command.messageId() == null) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        long ifMatch = support.ifMatch(command.ifMatch());
        String key = support.idempotencyKey(command.idempotencyKey());
        support.require(TenantPermission.MESSAGE_SEND);
        TenantContext context = support.currentContext();

        Claim claim = transactions.execute(
                status -> claim(context, command.messageId(), ifMatch, key, Pass.FIRST, status));
        if (claim instanceof Claim.NeedsProbe(TenantContext member, SenderRevision sender)) {
            healthGate.probe(MessagingProviderKey.META_WHATSAPP, member, sender);
            // From the start again: locks, every policy step, the rate check and the health check. The message, the
            // approval or the budget may have changed while the probe ran.
            claim = transactions.execute(
                    status -> claim(context, command.messageId(), ifMatch, key, Pass.AFTER_PROBE, status));
        }
        if (claim instanceof Claim.Replayed(CommandResult replayed)) {
            return replayed;
        }
        if (!(claim instanceof Claim.Started started)) {
            // The gate refuses a second probe itself, so this is a broken gate and nothing was claimed.
            throw new IllegalStateException("The provider health gate asked for a second probe");
        }
        ProviderCall call = call(started);
        try {
            CommandResult recorded = transactions.execute(status -> record(started, call.result()));
            recordHealthSignal(call.signal());
            return recorded;
        } catch (RuntimeException exception) {
            // The provider may have accepted; the STARTED attempt stays as the record of that, blocking any resend.
            log.error("Send outcome not recorded; sending halted for the message; messageId={}, tenantId={}, "
                    + "attempt={}", started.message().id(), started.message().tenantId(),
                    started.attempt().attemptNumber());
            throw exception;
        } finally {
            // Restored only now: JDBC and the pool refuse an interrupted thread, which would lose the outcome.
            if (call.interrupted()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private sealed interface Claim permits Claim.Replayed, Claim.Started, Claim.NeedsProbe {
        record Replayed(CommandResult result) implements Claim {
        }

        /**
         * Nothing was claimed and the transaction rolled back, so no send lock is held. {@code member} and
         * {@code sender} are what the policy just read; the probe uses that tenant's credential for that sender.
         */
        record NeedsProbe(TenantContext member, SenderRevision sender) implements Claim {
        }

        /** {@code member} is the membership as re-read under the lock; it authorized and is attributed the send. */
        record Started(TenantContext member, OutboundMessage message, SendAttempt attempt,
                OutboundSendCommand command) implements Claim {
        }
    }

    /**
     * The provider's answer, what the exchange said about the provider's health (empty when the call missed the
     * dispatch deadline, raised or tried no connection), and whether the waiting thread was interrupted (restored
     * after recording).
     */
    private record ProviderCall(ProviderSendResult result, Optional<ProviderSignalKind> signal,
            boolean interrupted) {
        static ProviderCall unknown(boolean interrupted) {
            return new ProviderCall(new ProviderSendResult.OutcomeUnknown(Optional.empty()), Optional.empty(),
                    interrupted);
        }
    }

    /**
     * Reported only once the send's own outcome is committed, outside the dispatch deadline and every send lock
     * (ADR 0038). The outcome above is final: a signal that cannot be stored never changes it.
     */
    private void recordHealthSignal(Optional<ProviderSignalKind> signal) {
        if (signal.isEmpty()) {
            return;
        }
        try {
            health.record(MessagingProviderKey.META_WHATSAPP, signal.get());
        } catch (RuntimeException failure) {
            log.warn("Provider health signal not recorded; errorType={}", failure.getClass().getSimpleName());
        }
    }

    /** One transaction: idempotency, customer then message lock, the full send policy, T6, T7 and the attempt row. */
    private Claim claim(TenantContext context, UUID messageId, long ifMatch, String key, Pass pass,
            TransactionStatus transaction) {
        UUID tenantId = context.tenantId().value();
        String fingerprint = RequestFingerprint.of(MessageOperation.SEND,
                List.of(messageId.toString(), Long.toString(ifMatch)));
        idempotency.acquireLock(tenantId, MessageOperation.SEND, key);
        var existing = idempotency.find(tenantId, MessageOperation.SEND, key);
        if (existing.isPresent()) {
            return new Claim.Replayed(replay(existing.get(), fingerprint, messageId));
        }

        OutboundMessage unlocked = messages.findById(tenantId, messageId)
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        Customer customer = customers.findByIdForUpdate(context.tenantId(), new CustomerId(unlocked.customerId()))
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        TenantContext member;
        try {
            member = members.requireResourceAccess(TenantPermission.MESSAGE_SEND, customer);
        } catch (TenantAccessDeniedException exception) {
            throw refuse(MessagingErrorCode.FORBIDDEN);
        }
        OutboundMessage message = messages.findByIdForUpdate(tenantId, messageId)
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        if (message.version() != ifMatch) {
            throw refuse(MessagingErrorCode.STALE_VERSION);
        }

        OutboundSendCommand send = gate.evaluate(member, customer, message, clock.instant());
        // The tenant rate bound is checked last and alone under its lock, and the clock is read after taking it: a
        // claim that waited behind another one counts the window and dates its attempt from when it actually holds
        // the budget, while the rest of the policy never serializes a tenant's sends (ADR 0024 §5).
        messages.acquireSendBudgetLock(tenantId);
        Instant now = clock.instant();
        gate.requireRateBudget(tenantId, now);
        // The last policy step, before the first write (ADR 0038): a refusal raises and rolls back, so the message
        // keeps its state, its version and its attempt count, and the tenant's send rate counts nothing.
        if (healthGate.admit(MessagingProviderKey.META_WHATSAPP, pass) == Admission.PROBE) {
            transaction.setRollbackOnly();
            return new Claim.NeedsProbe(member, send.expectedSender());
        }
        Transition requested = message.requestSend(member.membershipId().value(), now);
        OutboundMessage queued = messages.update(message, requested.next());
        messages.appendEvent(requested.event());
        Transition started = queued.startAttempt(now);
        OutboundMessage sending = messages.update(queued, started.next());
        messages.appendEvent(started.event());
        SendAttempt attempt = messages.insertAttempt(SendAttempt.started(sending, member.membershipId().value(),
                member.identityId().value(), now));
        idempotency.record(new MessageIdempotencyStore.IdempotencyRecord(UUID.randomUUID(), tenantId,
                MessageOperation.SEND, key, fingerprint, message.id(), attempt.id(), now));
        audit.sendRequested(requested.event());
        log.info("Send claimed; messageId={}, tenantId={}, attempt={}", message.id(), tenantId,
                attempt.attemptNumber());
        return new Claim.Started(member, sending, attempt, send);
    }

    /**
     * One provider call under the dispatch deadline ({@code dokene.messaging.send-timeout}, ADR 0023 §4.3), covering
     * the adapter's preparation, secret resolution and HTTP exchange. It runs on a virtual thread that binds the
     * member's context explicitly, because scoped context is never inherited. A call that misses the deadline is
     * interrupted and its outcome is unknown: a late answer is never recorded, since the send may still have landed.
     * The port promises never to throw; anything that escapes happened at an unknown point of the call too.
     */
    private ProviderCall call(Claim.Started started) {
        UUID messageId = started.message().id();
        CompletableFuture<MessagingProvider.Exchange> answer = new CompletableFuture<>();
        Thread caller = Thread.ofVirtual().name("dokene-send-" + messageId).start(() -> {
            try {
                answer.complete(contexts.callWithContext(started.member(),
                        () -> provider.exchange(started.command())));
            } catch (Throwable failure) {
                // Errors too: otherwise the future never completes and the request waits out the whole deadline.
                answer.completeExceptionally(failure);
            }
        });
        try {
            MessagingProvider.Exchange exchange = answer.get(gate.sendTimeout().toNanos(), TimeUnit.NANOSECONDS);
            if (exchange != null) {
                return new ProviderCall(exchange.result(), exchange.signal(), false);
            }
        } catch (TimeoutException timeout) {
            caller.interrupt();
            log.warn("Provider send missed the dispatch deadline; outcome unknown; messageId={}", messageId);
        } catch (ExecutionException failure) {
            log.warn("Provider send raised; outcome unknown; messageId={}, errorType={}", messageId,
                    failure.getCause().getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            caller.interrupt();
            log.warn("Dispatch interrupted while the provider call ran; outcome unknown; messageId={}", messageId);
            return ProviderCall.unknown(true);
        }
        return ProviderCall.unknown(false);
    }

    /** T8 to T11 under the customer then message locks, the same order delivery reports use. */
    private CommandResult record(Claim.Started started, ProviderSendResult result) {
        UUID tenantId = started.member().tenantId().value();
        UUID messageId = started.message().id();
        customers.findByIdForUpdate(started.member().tenantId(), new CustomerId(started.message().customerId()))
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        OutboundMessage current = messages.findByIdForUpdate(tenantId, messageId)
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        Instant now = clock.instant();
        Transition completed = current.completeAttempt(result, now, gate.maxSendAttempts());
        OutboundMessage next = messages.update(current, completed.next());
        messages.appendEvent(completed.event());
        SendAttempt attempt = messages.updateAttempt(started.attempt().completed(result, now));
        MessageEventType type = completed.event().type();
        switch (type) {
            case SENT -> {
                touches.recordOutboundMessage(new CustomerId(next.customerId()), next.sentAt());
                audit.sent(completed.event());
            }
            case SEND_FAILED -> audit.sendFailed(completed.event());
            case SEND_OUTCOME_UNKNOWN -> audit.sendOutcomeUnknown(completed.event());
            default -> throw new IllegalStateException("Unexpected attempt completion event " + type);
        }
        log.info("Send outcome recorded; messageId={}, tenantId={}, attempt={}, outcome={}, status={}", messageId,
                tenantId, attempt.attemptNumber(), attempt.outcome(), next.status());
        return support.result(tenantId, messageId, Optional.of(attempt), true);
    }

    private CommandResult replay(MessageIdempotencyStore.IdempotencyRecord record, String fingerprint,
            UUID targetMessageId) {
        if (!record.requestFingerprint().equals(fingerprint) || !record.messageId().equals(targetMessageId)) {
            throw refuse(MessagingErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        // Lock the message so the detail below is read from one state: every transition holds this row lock.
        messages.findByIdForUpdate(record.tenantId(), record.messageId())
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        MessageDetail detail = support.detail(record.tenantId(), record.messageId());
        Optional<Object> attempt = detail.attempts().stream()
                .filter(candidate -> candidate.id().equals(record.recordId())).findFirst().map(Object.class::cast);
        return new CommandResult(detail, attempt, false);
    }

    private static MessagingRefusedException refuse(MessagingErrorCode code) {
        return new MessagingRefusedException(code);
    }
}
