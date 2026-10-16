// ABOUTME: Manual dispatch (issue 138): claim (T6, T7, the attempt row) in one transaction, call the provider outside
// ABOUTME: any transaction, then record the outcome (T8 to T11) in a second one. Never resends on its own.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpTouchRecorder;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageOperation;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.messaging.domain.RequestFingerprint;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.messaging.domain.Transition;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextUnavailableException;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
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
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern IF_MATCH = Pattern.compile("^[0-9]{1,19}$");

    private final OutboundMessageRepository messages;
    private final MessageIdempotencyStore idempotency;
    private final CustomerRepository customers;
    private final SendPolicyGate gate;
    private final MessagingProvider provider;
    private final FollowUpTouchRecorder touches;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final MessageAuditPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OutboundDispatchService(OutboundMessageRepository messages, MessageIdempotencyStore idempotency,
            CustomerRepository customers, SendPolicyGate gate, MessagingProvider provider,
            FollowUpTouchRecorder touches, TenantAuthorizationService authorization, TenantContextProvider contexts,
            MessageAuditPort audit, PlatformTransactionManager transactionManager, Clock clock) {
        this.messages = Objects.requireNonNull(messages);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.customers = Objects.requireNonNull(customers);
        this.gate = Objects.requireNonNull(gate);
        this.provider = Objects.requireNonNull(provider);
        this.touches = Objects.requireNonNull(touches);
        this.authorization = Objects.requireNonNull(authorization);
        this.contexts = Objects.requireNonNull(contexts);
        this.audit = Objects.requireNonNull(audit);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * T6 to T11 for one operator request. A deterministic refusal happens before anything is written and before any
     * provider call. Once the attempt row is committed, every provider answer is a {@code created} result carrying
     * the message's new state (ADR 0023 §4.4); a replay of the same key returns the current state instead.
     */
    public CommandResult send(SendMessageCommand command) {
        Objects.requireNonNull(command, "Command is required");
        if (command.messageId() == null) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        long ifMatch = ifMatch(command.ifMatch());
        String key = idempotencyKey(command.idempotencyKey());
        require(TenantPermission.MESSAGE_SEND);
        TenantContext context = currentContext();

        Claim claim = transactions.execute(status -> claim(context, command.messageId(), ifMatch, key));
        if (claim instanceof Claim.Replayed(CommandResult replayed)) {
            return replayed;
        }
        Claim.Started started = (Claim.Started) claim;
        ProviderSendResult result = call(started);
        try {
            return transactions.execute(status -> record(context, started, result));
        } catch (RuntimeException exception) {
            // The provider may have accepted; the STARTED attempt stays as the record of that, blocking any resend.
            log.error("Send outcome not recorded; sending halted for the message; messageId={}, tenantId={}, "
                    + "attempt={}", started.message().id(), started.message().tenantId(),
                    started.attempt().attemptNumber());
            throw exception;
        }
    }

    private sealed interface Claim permits Claim.Replayed, Claim.Started {
        record Replayed(CommandResult result) implements Claim {
        }

        record Started(OutboundMessage message, SendAttempt attempt, OutboundSendCommand command) implements Claim {
        }
    }

    /** One transaction: idempotency, customer then message lock, the full send policy, T6, T7 and the attempt row. */
    private Claim claim(TenantContext context, UUID messageId, long ifMatch, String key) {
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
        try {
            authorization.requireResourceAccess(TenantPermission.MESSAGE_SEND, customer);
        } catch (TenantAccessDeniedException exception) {
            throw refuse(MessagingErrorCode.FORBIDDEN);
        }
        OutboundMessage message = messages.findByIdForUpdate(tenantId, messageId)
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        if (message.version() != ifMatch) {
            throw refuse(MessagingErrorCode.STALE_VERSION);
        }

        Instant now = clock.instant();
        OutboundSendCommand send = gate.evaluate(context, customer, message, now);
        Transition requested = message.requestSend(context.membershipId().value(), now);
        OutboundMessage queued = messages.update(message, requested.next());
        messages.appendEvent(requested.event());
        Transition started = queued.startAttempt(now);
        OutboundMessage sending = messages.update(queued, started.next());
        messages.appendEvent(started.event());
        SendAttempt attempt = messages.insertAttempt(SendAttempt.started(sending, context.membershipId().value(),
                context.identityId().value(), now));
        idempotency.record(new MessageIdempotencyStore.IdempotencyRecord(UUID.randomUUID(), tenantId,
                MessageOperation.SEND, key, fingerprint, message.id(), attempt.id(), now));
        audit.sendRequested(requested.event());
        log.info("Send claimed; messageId={}, tenantId={}, attempt={}", message.id(), tenantId,
                attempt.attemptNumber());
        return new Claim.Started(sending, attempt, send);
    }

    /** The port promises never to throw; anything that still escapes happened at an unknown point of the call. */
    private ProviderSendResult call(Claim.Started started) {
        try {
            ProviderSendResult result = provider.send(started.command());
            if (result != null) {
                return result;
            }
        } catch (RuntimeException exception) {
            log.warn("Provider send raised; outcome unknown; messageId={}, errorType={}", started.message().id(),
                    exception.getClass().getSimpleName());
        }
        return new ProviderSendResult.OutcomeUnknown(Optional.empty());
    }

    /** T8 to T11 under the customer then message locks, the same order delivery reports use. */
    private CommandResult record(TenantContext context, Claim.Started started, ProviderSendResult result) {
        UUID tenantId = context.tenantId().value();
        UUID messageId = started.message().id();
        customers.findByIdForUpdate(context.tenantId(), new CustomerId(started.message().customerId()))
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
        return result(tenantId, messageId, Optional.of(attempt), true);
    }

    private CommandResult replay(MessageIdempotencyStore.IdempotencyRecord record, String fingerprint,
            UUID targetMessageId) {
        if (!record.requestFingerprint().equals(fingerprint) || !record.messageId().equals(targetMessageId)) {
            throw refuse(MessagingErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        // Lock the message so the detail below is read from one state: every transition holds this row lock.
        messages.findByIdForUpdate(record.tenantId(), record.messageId())
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        Optional<Object> attempt = messages.findAttempts(record.tenantId(), record.messageId()).stream()
                .filter(candidate -> candidate.id().equals(record.recordId())).findFirst().map(Object.class::cast);
        return result(record.tenantId(), record.messageId(), attempt, false);
    }

    private CommandResult result(UUID tenantId, UUID messageId, Optional<Object> record, boolean created) {
        var listed = messages.findListedById(tenantId, messageId)
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        return new CommandResult(new MessageDetail(listed.message(), listed.contactRemoved(),
                messages.findApprovalByMessage(tenantId, messageId), messages.findAttempts(tenantId, messageId)),
                record, created);
    }

    private static long ifMatch(String value) {
        if (value == null || !IF_MATCH.matcher(value.strip()).matches()) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException exception) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
    }

    private static String idempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        return value;
    }

    private void require(TenantPermission permission) {
        try {
            authorization.requirePermission(permission);
        } catch (TenantAccessDeniedException exception) {
            throw refuse(MessagingErrorCode.FORBIDDEN);
        }
    }

    private TenantContext currentContext() {
        try {
            return contexts.requireCurrent();
        } catch (TenantContextUnavailableException exception) {
            throw refuse(MessagingErrorCode.FORBIDDEN);
        }
    }

    private static MessagingRefusedException refuse(MessagingErrorCode code) {
        return new MessagingRefusedException(code);
    }
}
