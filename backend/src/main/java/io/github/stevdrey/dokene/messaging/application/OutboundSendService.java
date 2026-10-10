// ABOUTME: The three phase send of spec 0003: policy and attempt row committed, provider call, completion committed.
// ABOUTME: Phases 1 and 3 are TransactionTemplate callbacks; phase 2 runs with no transaction and no row lock.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpTouchRecorder;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.MessageOperation;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.messaging.domain.RequestFingerprint;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.messaging.domain.SendAttemptOutcome;
import io.github.stevdrey.dokene.messaging.domain.Transition;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextUnavailableException;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OutboundSendService {
    static final String ATTEMPTS = "dokene.messaging.send.attempts";
    static final String DURATION = "dokene.messaging.send.duration";
    static final String COMPLETION_FAILED = "COMPLETION_FAILED";
    private static final Logger log = LoggerFactory.getLogger(OutboundSendService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern IF_MATCH = Pattern.compile("^[0-9]{1,19}$");

    private final OutboundMessageRepository messages;
    private final MessageIdempotencyStore idempotency;
    private final CustomerRepository customers;
    private final OutboundMessageCommandService guards;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final MessageAuditPort audit;
    private final FollowUpTouchRecorder touches;
    private final OutboundGate gate;
    private final OutboundSendRateLimiter rateLimiter;
    private final ObjectProvider<MessagingProvider> providers;
    private final OutboundMessagingProperties properties;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final MeterRegistry registry;
    private final Clock clock;

    public OutboundSendService(OutboundMessageRepository messages, MessageIdempotencyStore idempotency,
            CustomerRepository customers, OutboundMessageCommandService guards,
            TenantAuthorizationService authorization, TenantContextProvider contexts, MessageAuditPort audit,
            FollowUpTouchRecorder touches, OutboundGate gate, OutboundSendRateLimiter rateLimiter,
            ObjectProvider<MessagingProvider> providers, OutboundMessagingProperties properties,
            PlatformTransactionManager transactionManager, ApplicationEventPublisher events,
            ObjectProvider<MeterRegistry> registry, Clock clock) {
        this.messages = Objects.requireNonNull(messages);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.customers = Objects.requireNonNull(customers);
        this.guards = Objects.requireNonNull(guards);
        this.authorization = Objects.requireNonNull(authorization);
        this.contexts = Objects.requireNonNull(contexts);
        this.audit = Objects.requireNonNull(audit);
        this.touches = Objects.requireNonNull(touches);
        this.gate = Objects.requireNonNull(gate);
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        this.providers = Objects.requireNonNull(providers);
        this.properties = Objects.requireNonNull(properties);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.events = Objects.requireNonNull(events);
        this.registry = Objects.requireNonNull(registry).getIfAvailable();
        this.clock = Objects.requireNonNull(clock);
    }

    /** T6 to T11 for one request; a replay returns the current representation with created false. */
    public CommandResult send(SendMessageCommand command) {
        Input input = validate(command);
        AcceptResult accepted = transactions.execute(status -> accept(input));
        return switch (accepted) {
            case AcceptResult.Replayed replayed -> replayed.result();
            case AcceptResult.Accepted attempt -> {
                MessagingProvider provider = providers.getIfAvailable();
                ProviderSendResult result = callProvider(attempt, provider);
                OutboundMessage completed = completeOrFail(attempt, result);
                yield new CommandResult(completed, Optional.of(attempt.attempt()), true);
            }
        };
    }

    /** Phase 1: the eleven step policy, then T6 and T7 with the attempt row and the idempotency record. */
    AcceptResult accept(Input input) {
        TenantContext context = currentContext();
        require(TenantPermission.MESSAGE_SEND);
        UUID tenantId = context.tenantId().value();
        String fingerprint = RequestFingerprint.of(MessageOperation.SEND,
                Arrays.asList(input.messageId().toString(), Long.toString(input.ifMatch())));
        idempotency.acquireLock(tenantId, MessageOperation.SEND, input.idempotencyKey());
        var replay = idempotency.find(tenantId, MessageOperation.SEND, input.idempotencyKey());
        if (replay.isPresent()) {
            return new AcceptResult.Replayed(replay(replay.get(), fingerprint, input.messageId()));
        }
        rateLimiter.acquire(tenantId).ifPresent(wait -> {
            throw new MessagingRefusedException(MessagingErrorCode.RATE_LIMITED, wait);
        });
        if (!properties.outbound().enabled()) {
            throw refuse(MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY);
        }
        if (providers.getIfAvailable() == null) {
            throw refuse(MessagingErrorCode.PROVIDER_UNAVAILABLE);
        }
        switch (gate.check()) {
            case ENABLED -> { }
            case OUTBOUND_DISABLED_FOR_TENANT -> throw refuse(MessagingErrorCode.OUTBOUND_DISABLED_FOR_TENANT);
            case INTEGRATION_DISABLED -> throw refuse(MessagingErrorCode.INTEGRATION_DISABLED);
        }

        OutboundMessage message = guards.lockMessage(context, input.messageId(), input.ifMatch());
        if (message.status() != MessageStatus.APPROVED || message.outcomeUnknown()) {
            throw refuse(MessagingErrorCode.INVALID_TRANSITION);
        }
        boolean approved = messages.findApprovalByMessage(tenantId, message.id())
                .map(decision -> decision.decision() == ApprovalDecision.APPROVE).orElse(false);
        if (!approved) {
            throw refuse(MessagingErrorCode.INVALID_TRANSITION);
        }
        // The customer row is already locked by lockMessage; this read sees the locked version.
        Customer customer = customers.findById(context.tenantId(), new CustomerId(message.customerId()))
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        guards.requireActive(customer);
        guards.requireOwnedContact(customer, message.contactId());
        guards.requireConsent(customer, message.contactId());
        FollowUpEvaluation evaluation = guards.requireEligible(customer).evaluation();
        if (messages.findOpenByCustomer(tenantId, message.customerId(), Optional.of(message.id())).isPresent()) {
            throw refuse(MessagingErrorCode.MESSAGE_ALREADY_OPEN);
        }
        requireFrequency(tenantId, message.customerId(), evaluation);
        ResolvedTemplate template = guards.requireMapped(message.templateIntent());
        if (message.attemptCount() >= properties.maxSendAttempts()) {
            throw refuse(MessagingErrorCode.SEND_ATTEMPTS_EXHAUSTED);
        }
        guards.requireBodySafe(customer, message.action(), message.templateIntent(), message.body(),
                message.locale());

        Instant now = clock.instant();
        Transition requested = message.requestSend(context.membershipId().value(), now);
        OutboundMessage queued = messages.update(message, requested.next());
        messages.appendEvent(requested.event());
        Transition started = queued.startAttempt(now);
        OutboundMessage sending = messages.update(queued, started.next());
        messages.appendEvent(started.event());
        SendAttempt attempt = messages.insertAttempt(SendAttempt.started(sending, context.membershipId().value(),
                context.identityId().value(), now));
        idempotency.record(new MessageIdempotencyStore.IdempotencyRecord(UUID.randomUUID(), tenantId,
                MessageOperation.SEND, input.idempotencyKey(), fingerprint, sending.id(), attempt.id(), now));
        audit.sendRequested(requested.event());
        log.info("Send accepted; messageId={}, tenantId={}, attemptNumber={}", sending.id(), tenantId,
                attempt.attemptNumber());
        return new AcceptResult.Accepted(sending, attempt, template);
    }

    /** Step 8: a message sent strictly after the cadence anchor date means the cadence already advanced. */
    private void requireFrequency(UUID tenantId, UUID customerId, FollowUpEvaluation evaluation) {
        LocalDate anchor = evaluation.anchorDate();
        if (anchor == null) {
            return;
        }
        Instant bound = anchor.plusDays(1).atStartOfDay(evaluation.tenantZone()).toInstant();
        Optional<Instant> latest = messages.findLatestSentAt(tenantId, customerId);
        if (latest.isPresent() && !latest.get().isBefore(bound)) {
            throw refuse(MessagingErrorCode.CONTACT_FREQUENCY_EXCEEDED);
        }
    }

    /** Phase 2: no transaction, no lock; anything thrown is an unknown outcome, never a transient failure. */
    private ProviderSendResult callProvider(AcceptResult.Accepted accepted, MessagingProvider provider) {
        OutboundMessage message = accepted.message();
        if (provider == null) {
            log.error("Provider vanished between phases; messageId={}, tenantId={}", message.id(), message.tenantId());
            return new ProviderSendResult.OutcomeUnknown(Optional.empty());
        }
        OutboundSendCommand command = new OutboundSendCommand(message.recipientPhone(),
                accepted.template().providerTemplateName(), accepted.template().language(),
                List.of(message.body()), message.locale(), message.sendKey(), message.tenantId());
        Timer.Sample sample = registry == null ? null : Timer.start(registry);
        try {
            return Objects.requireNonNull(provider.send(command), "Provider returned no result");
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable thrown) {
            if (thrown instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Provider call threw; exception={}, messageId={}, tenantId={}", thrown.getClass().getName(),
                    message.id(), message.tenantId());
            return new ProviderSendResult.OutcomeUnknown(Optional.empty());
        } finally {
            if (sample != null) {
                sample.stop(registry.timer(DURATION));
            }
        }
    }

    /** Phase 3 wrapper: a failure here is visible (error, log line, counter) and leaves SENDING with a STARTED row. */
    private OutboundMessage completeOrFail(AcceptResult.Accepted accepted, ProviderSendResult result) {
        try {
            return transactions.execute(status -> complete(accepted, result));
        } catch (RuntimeException exception) {
            log.error("Send completion failed; messageId={}, tenantId={}, exception={}", accepted.message().id(),
                    accepted.message().tenantId(), exception.getClass().getName());
            count(COMPLETION_FAILED);
            throw exception;
        }
    }

    /** Phase 3: relock, write only if still SENDING with that attempt STARTED, T8 to T11, touch on SENT, audit. */
    OutboundMessage complete(AcceptResult.Accepted accepted, ProviderSendResult result) {
        TenantContext context = currentContext();
        UUID tenantId = context.tenantId().value();
        UUID messageId = accepted.message().id();
        customers.findByIdForUpdate(context.tenantId(), new CustomerId(accepted.message().customerId()))
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        OutboundMessage current = messages.findByIdForUpdate(tenantId, messageId)
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        SendAttempt attempt = messages.findAttempts(tenantId, messageId).stream()
                .filter(row -> row.id().equals(accepted.attempt().id())).findFirst().orElse(null);
        if (current.status() != MessageStatus.SENDING || current.outcomeUnknown() || attempt == null
                || attempt.outcome() != SendAttemptOutcome.STARTED) {
            log.warn("Attempt completion skipped, message moved meanwhile; messageId={}, tenantId={}, status={}",
                    messageId, tenantId, current.status());
            return current;
        }
        Instant now = clock.instant();
        Transition transition = current.completeAttempt(result, now, properties.maxSendAttempts());
        OutboundMessage next = messages.update(current, transition.next());
        messages.appendEvent(transition.event());
        SendAttempt completed = messages.updateAttempt(attempt.completed(result, now));
        switch (transition.event().type()) {
            case SENT -> {
                touches.recordOutboundMessage(new CustomerId(next.customerId()), next.sentAt());
                audit.sent(transition.event());
            }
            case SEND_FAILED -> audit.sendFailed(transition.event());
            case SEND_OUTCOME_UNKNOWN -> audit.sendOutcomeUnknown(transition.event());
            default -> throw new IllegalStateException("Unexpected completion event " + transition.event().type());
        }
        events.publishEvent(new SendAttemptCompleted(tenantId, messageId,
                Optional.ofNullable(completed.providerMessageId()), completed.outcome()));
        count(completed.outcome().name());
        log.info("Send completed; messageId={}, tenantId={}, outcome={}, status={}", messageId, tenantId,
                completed.outcome(), next.status());
        return next;
    }

    private CommandResult replay(MessageIdempotencyStore.IdempotencyRecord record, String fingerprint,
            UUID messageId) {
        if (!record.requestFingerprint().equals(fingerprint) || !record.messageId().equals(messageId)) {
            throw refuse(MessagingErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        OutboundMessage message = messages.findById(record.tenantId(), record.messageId())
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        Optional<Object> attempt = messages.findAttempts(record.tenantId(), record.messageId()).stream()
                .filter(row -> row.id().equals(record.recordId())).findFirst().map(Object.class::cast);
        return new CommandResult(message, attempt, false);
    }

    private void count(String outcome) {
        if (registry != null) {
            registry.counter(ATTEMPTS, "outcome", outcome).increment();
        }
    }

    record Input(UUID messageId, long ifMatch, String idempotencyKey) {
    }

    private static Input validate(SendMessageCommand command) {
        Objects.requireNonNull(command, "Command is required");
        if (command.messageId() == null || command.ifMatch() == null
                || !IF_MATCH.matcher(command.ifMatch().strip()).matches() || command.idempotencyKey() == null
                || !IDEMPOTENCY_KEY.matcher(command.idempotencyKey()).matches()) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        try {
            return new Input(command.messageId(), Long.parseLong(command.ifMatch().strip()), command.idempotencyKey());
        } catch (NumberFormatException exception) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
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
