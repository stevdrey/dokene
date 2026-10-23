// ABOUTME: Transactional T1 to T5 command services: submit, approve (binding the approval fingerprint), reject, cancel.
// ABOUTME: Order: validate, permission, idempotency lock, customer lock, message lock, guards, domain, persist, audit.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageCancellation;
import io.github.stevdrey.dokene.messaging.domain.MessageOperation;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingLocales;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.RequestFingerprint;
import io.github.stevdrey.dokene.messaging.domain.Transition;
import io.github.stevdrey.dokene.tenant.application.CurrentMembershipAuthorizer;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(EnglishOutboundProperties.class)
public class OutboundMessageCommandService {
    private static final Logger log = LoggerFactory.getLogger(OutboundMessageCommandService.class);
    private static final int MAX_NOTE_LENGTH = 500;

    private final EnglishOutboundProperties english;
    private final OutboundMessageRepository messages;
    private final MessageIdempotencyStore idempotency;
    private final CustomerRepository customers;
    private final MessageSendGuards guards;
    private final ApprovalBindingAssembler bindings;
    private final OutboundSafetyControl safety;
    private final MessageCommandSupport support;
    private final CurrentMembershipAuthorizer members;
    private final MessageAuditPort audit;
    private final Clock clock;

    public OutboundMessageCommandService(OutboundMessageRepository messages, MessageIdempotencyStore idempotency,
            CustomerRepository customers, MessageSendGuards guards, ApprovalBindingAssembler bindings,
            OutboundSafetyControl safety, MessageCommandSupport support, CurrentMembershipAuthorizer members,
            MessageAuditPort audit, Clock clock, EnglishOutboundProperties english) {
        this.english = Objects.requireNonNull(english);
        this.messages = Objects.requireNonNull(messages);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.customers = Objects.requireNonNull(customers);
        this.guards = Objects.requireNonNull(guards);
        this.bindings = Objects.requireNonNull(bindings);
        this.safety = Objects.requireNonNull(safety);
        this.support = Objects.requireNonNull(support);
        this.members = Objects.requireNonNull(members);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    /** T1. */
    @Transactional
    public CommandResult submit(SubmitMessageCommand command) {
        var input = validate(command);
        support.require(TenantPermission.MESSAGE_DRAFT);
        TenantContext context = support.currentContext();
        UUID tenantId = context.tenantId().value();
        String fingerprint = RequestFingerprint.of(MessageOperation.SUBMIT, Arrays.asList(
                command.customerId().toString(), command.contactId().toString(), input.action().name(),
                input.templateIntent().name(), input.body(), input.locale(), input.origin().name(),
                Long.toString(input.ifMatch())));
        idempotency.acquireLock(tenantId, MessageOperation.SUBMIT, input.idempotencyKey());
        var replay = idempotency.find(tenantId, MessageOperation.SUBMIT, input.idempotencyKey());
        if (replay.isPresent()) {
            return replay(replay.get(), fingerprint, null);
        }
        // Writing en-US waits for the deployment switch, so no row an earlier release cannot read exists before it is
        // on. It is checked after the replay lookup, so a submission committed while it was on still replays.
        if (MessagingLocales.ENGLISH_US.equals(input.locale()) && !english.enabled()) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }

        Customer customer = customers.findByIdForUpdate(context.tenantId(), new CustomerId(command.customerId()))
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        context = currentMember(TenantPermission.MESSAGE_DRAFT, customer);
        CustomerPhone contact = customer.phones().stream()
                .filter(phone -> phone.id().equals(command.contactId())).findFirst()
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        guards.check(customer, contact.id(), input.action(), input.templateIntent(), input.body(),
                input.locale(), Optional.of(input.ifMatch()));
        // The same template, language and parameter checks approval binds, so an unapprovable message is never stored.
        bindings.requireRenderable(input.templateIntent(), input.locale(), input.body(), customer);
        if (messages.findOpenByCustomer(tenantId, customer.id().value(), Optional.empty()).isPresent()) {
            throw refuse(MessagingErrorCode.MESSAGE_ALREADY_OPEN);
        }

        Instant now = clock.instant();
        Transition transition = OutboundMessage.submit(new OutboundMessage.Submission(UUID.randomUUID(), tenantId,
                customer.id().value(), contact.id(), contact.e164(), input.action(), input.templateIntent(),
                input.locale(), input.origin(), input.body(), input.ifMatch(), UUID.randomUUID(),
                context.membershipId().value(), context.identityId().value()), now);
        OutboundMessage stored = messages.insert(transition.next());
        messages.appendEvent(transition.event());
        idempotency.record(new MessageIdempotencyStore.IdempotencyRecord(UUID.randomUUID(), tenantId,
                MessageOperation.SUBMIT, input.idempotencyKey(), fingerprint, stored.id(), null, now));
        audit.submitted(transition.event());
        log.info("Message submitted; messageId={}, tenantId={}, status={}", stored.id(), tenantId, stored.status());
        return support.result(tenantId, stored.id(), Optional.empty(), true);
    }

    /** T2. */
    @Transactional
    public CommandResult approve(ApprovalCommand command) {
        return decide(command, ApprovalDecision.APPROVE);
    }

    /** T3. */
    @Transactional
    public CommandResult reject(ApprovalCommand command) {
        return decide(command, ApprovalDecision.REJECT);
    }

    /** T4 from PENDING_APPROVAL with MESSAGE_DRAFT, T5 from APPROVED with MESSAGE_APPROVE. */
    @Transactional
    public CommandResult cancel(CancellationCommand command) {
        Objects.requireNonNull(command, "Command is required");
        var input = validateChild(command.messageId(), command.note(), command.ifMatch(), command.idempotencyKey());
        // Either permission admits the request; the state specific one is enforced after the message lock.
        if (!support.allows(TenantPermission.MESSAGE_DRAFT) && !support.allows(TenantPermission.MESSAGE_APPROVE)) {
            support.require(TenantPermission.MESSAGE_APPROVE);
        }
        TenantContext context = support.currentContext();
        UUID tenantId = context.tenantId().value();
        String fingerprint = RequestFingerprint.of(MessageOperation.CANCELLATION, Arrays.asList(
                command.messageId().toString(), input.note(), Long.toString(input.ifMatch())));
        idempotency.acquireLock(tenantId, MessageOperation.CANCELLATION, input.idempotencyKey());
        var replay = idempotency.find(tenantId, MessageOperation.CANCELLATION, input.idempotencyKey());
        if (replay.isPresent()) {
            return replay(replay.get(), fingerprint, command.messageId());
        }

        Locked locked = lockMessage(context, command.messageId(), input.ifMatch());
        OutboundMessage message = locked.message();
        context = switch (message.status()) {
            case PENDING_APPROVAL -> currentMember(TenantPermission.MESSAGE_DRAFT, locked.customer());
            case APPROVED -> currentMember(TenantPermission.MESSAGE_APPROVE, locked.customer());
            default -> throw refuse(MessagingErrorCode.INVALID_TRANSITION);
        };
        Instant now = clock.instant();
        Transition transition = message.cancel(context.membershipId().value(), now);
        OutboundMessage stored = messages.update(message, transition.next());
        MessageCancellation cancellation = messages.insertCancellation(new MessageCancellation(UUID.randomUUID(),
                tenantId, message.id(), message.status(), input.note(), now, context.membershipId().value(),
                context.identityId().value()));
        messages.appendEvent(transition.event());
        idempotency.record(new MessageIdempotencyStore.IdempotencyRecord(UUID.randomUUID(), tenantId,
                MessageOperation.CANCELLATION, input.idempotencyKey(), fingerprint, stored.id(), cancellation.id(),
                now));
        audit.cancelled(transition.event());
        log.info("Message cancelled; messageId={}, tenantId={}, statusFrom={}, statusTo={}", stored.id(), tenantId,
                message.status(), stored.status());
        return support.result(tenantId, stored.id(), Optional.of(cancellation), true);
    }

    private CommandResult decide(ApprovalCommand command, ApprovalDecision decision) {
        Objects.requireNonNull(command, "Command is required");
        var input = validateChild(command.messageId(), command.note(), command.ifMatch(), command.idempotencyKey());
        support.require(TenantPermission.MESSAGE_APPROVE);
        TenantContext context = support.currentContext();
        UUID tenantId = context.tenantId().value();
        List<String> fields = new ArrayList<>(Arrays.asList(command.messageId().toString(), decision.name(),
                input.note(), Long.toString(input.ifMatch())));
        // A request without a revision keeps the earlier four-field form, so a decision committed by the previous
        // release still replays after the upgrade.
        if (command.templateRevision() != null) {
            fields.add(command.templateRevision());
        }
        String fingerprint = RequestFingerprint.of(MessageOperation.APPROVAL, fields);
        idempotency.acquireLock(tenantId, MessageOperation.APPROVAL, input.idempotencyKey());
        var replay = idempotency.find(tenantId, MessageOperation.APPROVAL, input.idempotencyKey());
        if (replay.isPresent()) {
            return replay(replay.get(), fingerprint, command.messageId());
        }

        Locked locked = lockMessage(context, command.messageId(), input.ifMatch());
        OutboundMessage message = locked.message();
        context = currentMember(TenantPermission.MESSAGE_APPROVE, locked.customer());
        if (message.status() != MessageStatus.PENDING_APPROVAL) {
            throw refuse(MessagingErrorCode.INVALID_TRANSITION);
        }
        Instant now = clock.instant();
        Transition transition;
        MessageApproval approval;
        if (decision == ApprovalDecision.APPROVE) {
            Customer customer = locked.customer();
            // T2 step 4 (AC-13): the contact has no foreign key, so a phone correction may have removed it.
            if (customer.phones().stream().noneMatch(phone -> phone.id().equals(message.contactId()))) {
                throw refuse(MessagingErrorCode.CONTACT_NOT_OWNED);
            }
            guards.check(customer, message.contactId(), message.action(), message.templateIntent(),
                    message.body(), message.locale(), Optional.empty());
            if (messages.findOpenByCustomer(tenantId, message.customerId(), Optional.of(message.id())).isPresent()) {
                throw refuse(MessagingErrorCode.MESSAGE_ALREADY_OPEN);
            }
            // ADR 0024 §2: bind what is approved under the customer lock. No integration means no sender to bind;
            // kill switches are not checked here, so approving while sending is off is allowed.
            ApprovalFingerprint.SenderRevision sender = safety.configuredSender(context.tenantId())
                    .orElseThrow(() -> refuse(MessagingErrorCode.INTEGRATION_DISABLED));
            ApprovalFingerprint.Binding bound = bindings.bind(message, customer, sender);
            // The reviewer approved the template preview it was shown; a template changed since then is stale.
            if (command.templateRevision() != null && !command.templateRevision()
                    .equals(ApprovalFingerprint.reviewRevision(bound.template(), bound.parameterValues()))) {
                throw refuse(MessagingErrorCode.STALE_VERSION);
            }
            ApprovalFingerprint binding = ApprovalFingerprint.of(bound);
            transition = message.approve(context.membershipId().value(), now);
            approval = MessageApproval.approve(UUID.randomUUID(), tenantId, message.id(), input.note(), binding,
                    now, context.membershipId().value(), context.identityId().value());
        } else {
            transition = message.reject(context.membershipId().value(), now);
            approval = MessageApproval.reject(UUID.randomUUID(), tenantId, message.id(), input.note(), now,
                    context.membershipId().value(), context.identityId().value());
        }
        OutboundMessage stored = messages.update(message, transition.next());
        messages.insertApproval(approval);
        messages.appendEvent(transition.event());
        idempotency.record(new MessageIdempotencyStore.IdempotencyRecord(UUID.randomUUID(), tenantId,
                MessageOperation.APPROVAL, input.idempotencyKey(), fingerprint, stored.id(), approval.id(), now));
        if (decision == ApprovalDecision.APPROVE) {
            audit.approved(transition.event());
        } else {
            audit.rejected(transition.event());
        }
        log.info("Message decided; messageId={}, tenantId={}, statusFrom={}, statusTo={}", stored.id(), tenantId,
                message.status(), stored.status());
        return support.result(tenantId, stored.id(), Optional.of(approval), true);
    }

    /**
     * The member as the membership is now, allowed {@code permission} on the locked customer. Called under the
     * customer lock, so a revocation or demotion committed before it refuses the command (audited); the returned
     * context attributes everything the command writes.
     */
    private TenantContext currentMember(TenantPermission permission, Customer customer) {
        try {
            return members.requireResourceAccess(permission, customer);
        } catch (TenantAccessDeniedException exception) {
            throw refuse(MessagingErrorCode.FORBIDDEN);
        }
    }

    private record Locked(Customer customer, OutboundMessage message) {
    }

    /** Customer lock, then message lock, then the If-Match check (T2 to T5 guard steps 1 and 2). */
    private Locked lockMessage(TenantContext context, UUID messageId, long ifMatch) {
        UUID tenantId = context.tenantId().value();
        OutboundMessage unlocked = messages.findById(tenantId, messageId)
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        Customer customer = customers.findByIdForUpdate(context.tenantId(), new CustomerId(unlocked.customerId()))
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        OutboundMessage message = messages.findByIdForUpdate(tenantId, messageId)
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        if (message.version() != ifMatch) {
            throw refuse(MessagingErrorCode.STALE_VERSION);
        }
        return new Locked(customer, message);
    }

    private CommandResult replay(MessageIdempotencyStore.IdempotencyRecord record, String fingerprint,
            UUID targetMessageId) {
        if (!record.requestFingerprint().equals(fingerprint)
                || (targetMessageId != null && !record.messageId().equals(targetMessageId))) {
            throw refuse(MessagingErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        // Lock the message so the detail below is read from one state: every transition holds this row lock.
        messages.findByIdForUpdate(record.tenantId(), record.messageId())
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        Optional<Object> child = switch (record.operation()) {
            case SUBMIT -> Optional.empty();
            case APPROVAL -> messages.findApproval(record.tenantId(), record.recordId()).map(Object.class::cast);
            case CANCELLATION -> messages.findCancellation(record.tenantId(), record.recordId())
                    .map(Object.class::cast);
            case SEND, RESOLUTION -> Optional.empty();
        };
        return support.result(record.tenantId(), record.messageId(), child, false);
    }

    private record SubmitInput(SemanticAction action, SemanticTemplateIntent templateIntent, String body,
            String locale, MessageOrigin origin, long ifMatch, String idempotencyKey) {
    }

    private record ChildInput(String note, long ifMatch, String idempotencyKey) {
    }

    private SubmitInput validate(SubmitMessageCommand command) {
        Objects.requireNonNull(command, "Command is required");
        if (command.customerId() == null || command.contactId() == null || command.body() == null
                || command.body().isBlank() || containsNul(command.body())
                || command.body().codePointCount(0, command.body().length()) > OutboundMessage.MAX_BODY_LENGTH
                || !MessagingLocales.supported(command.locale())) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        SemanticAction action = parse(SemanticAction.class, command.action());
        SemanticTemplateIntent intent = parse(SemanticTemplateIntent.class, command.templateIntent());
        MessageOrigin origin = parse(MessageOrigin.class, command.origin());
        // AI drafts are generated and safety-checked in the default locale only (ADR 0018); another locale is manual.
        if (origin == MessageOrigin.AI_DRAFT && !MessagingLocales.DEFAULT.equals(command.locale())) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        return new SubmitInput(action, intent, command.body(), command.locale(), origin,
                support.ifMatch(command.ifMatch()), support.idempotencyKey(command.idempotencyKey()));
    }

    private ChildInput validateChild(UUID messageId, String note, String ifMatch, String idempotencyKey) {
        if (messageId == null || (note != null && (note.length() > MAX_NOTE_LENGTH || containsNul(note)))) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        return new ChildInput(note, support.ifMatch(ifMatch), support.idempotencyKey(idempotencyKey));
    }

    /** Postgres cannot store U+0000 in text columns; refuse it as input rather than fail at the insert. */
    private static boolean containsNul(String value) {
        return value.indexOf('\0') >= 0;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
    }

    private static MessagingRefusedException refuse(MessagingErrorCode code) {
        return new MessagingRefusedException(code);
    }
}
