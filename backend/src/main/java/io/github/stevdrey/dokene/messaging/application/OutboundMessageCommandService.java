// ABOUTME: Transactional T1 to T5 command services: submit, approve, reject and cancel (spec 0001).
// ABOUTME: Order: validate, permission, idempotency lock, customer lock, message lock, guards, domain, persist, audit.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.ai.domain.DraftSafetyValidator;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.RecommendationValidationException;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactPolicy;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.customer.domain.CustomerStatus;
import io.github.stevdrey.dokene.followup.application.DraftGroundingAssembler;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
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
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextUnavailableException;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboundMessageCommandService {
    private static final Logger log = LoggerFactory.getLogger(OutboundMessageCommandService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern IF_MATCH = Pattern.compile("^[0-9]{1,19}$");
    private static final int MAX_NOTE_LENGTH = 500;
    // Fixed, context free rationale: the validator checks it with the body and it must never fabricate facts.
    private static final String OPERATOR_RATIONALE = "Mensaje enviado por el operador.";

    private final OutboundMessageRepository messages;
    private final MessageIdempotencyStore idempotency;
    private final CustomerRepository customers;
    private final ContactPolicyRepository contactPolicies;
    private final FollowUpService followUps;
    private final DraftGroundingAssembler grounding;
    private final TemplateMappingGate templates;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final MessageAuditPort audit;
    private final Clock clock;

    public OutboundMessageCommandService(OutboundMessageRepository messages, MessageIdempotencyStore idempotency,
            CustomerRepository customers, ContactPolicyRepository contactPolicies, FollowUpService followUps,
            DraftGroundingAssembler grounding, TemplateMappingGate templates,
            TenantAuthorizationService authorization, TenantContextProvider contexts, MessageAuditPort audit,
            Clock clock) {
        this.messages = Objects.requireNonNull(messages);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.customers = Objects.requireNonNull(customers);
        this.contactPolicies = Objects.requireNonNull(contactPolicies);
        this.followUps = Objects.requireNonNull(followUps);
        this.grounding = Objects.requireNonNull(grounding);
        this.templates = Objects.requireNonNull(templates);
        this.authorization = Objects.requireNonNull(authorization);
        this.contexts = Objects.requireNonNull(contexts);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    /** T1. */
    @Transactional
    public CommandResult submit(SubmitMessageCommand command) {
        var input = validate(command);
        require(TenantPermission.MESSAGE_DRAFT);
        TenantContext context = currentContext();
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

        Customer customer = lockCustomer(context, command.customerId(), TenantPermission.MESSAGE_DRAFT);
        CustomerPhone contact = customer.phones().stream()
                .filter(phone -> phone.id().equals(command.contactId())).findFirst()
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        runSharedGuards(customer, contact.id(), input.action(), input.templateIntent(), input.body(),
                input.locale(), Optional.of(input.ifMatch()));
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
        return new CommandResult(stored, Optional.empty(), true);
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
        if (!authorization.evaluate(TenantPermission.MESSAGE_DRAFT).isAllowed()
                && !authorization.evaluate(TenantPermission.MESSAGE_APPROVE).isAllowed()) {
            require(TenantPermission.MESSAGE_APPROVE);
        }
        TenantContext context = currentContext();
        UUID tenantId = context.tenantId().value();
        String fingerprint = RequestFingerprint.of(MessageOperation.CANCELLATION, Arrays.asList(
                command.messageId().toString(), input.note(), Long.toString(input.ifMatch())));
        idempotency.acquireLock(tenantId, MessageOperation.CANCELLATION, input.idempotencyKey());
        var replay = idempotency.find(tenantId, MessageOperation.CANCELLATION, input.idempotencyKey());
        if (replay.isPresent()) {
            return replay(replay.get(), fingerprint, command.messageId());
        }

        OutboundMessage message = lockMessage(context, command.messageId(), input.ifMatch());
        switch (message.status()) {
            case PENDING_APPROVAL -> require(TenantPermission.MESSAGE_DRAFT);
            case APPROVED -> require(TenantPermission.MESSAGE_APPROVE);
            default -> throw refuse(MessagingErrorCode.INVALID_TRANSITION);
        }
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
        return new CommandResult(stored, Optional.of(cancellation), true);
    }

    private CommandResult decide(ApprovalCommand command, ApprovalDecision decision) {
        Objects.requireNonNull(command, "Command is required");
        var input = validateChild(command.messageId(), command.note(), command.ifMatch(), command.idempotencyKey());
        require(TenantPermission.MESSAGE_APPROVE);
        TenantContext context = currentContext();
        UUID tenantId = context.tenantId().value();
        String fingerprint = RequestFingerprint.of(MessageOperation.APPROVAL, Arrays.asList(
                command.messageId().toString(), decision.name(), input.note(), Long.toString(input.ifMatch())));
        idempotency.acquireLock(tenantId, MessageOperation.APPROVAL, input.idempotencyKey());
        var replay = idempotency.find(tenantId, MessageOperation.APPROVAL, input.idempotencyKey());
        if (replay.isPresent()) {
            return replay(replay.get(), fingerprint, command.messageId());
        }

        OutboundMessage message = lockMessage(context, command.messageId(), input.ifMatch());
        if (message.status() != MessageStatus.PENDING_APPROVAL) {
            throw refuse(MessagingErrorCode.INVALID_TRANSITION);
        }
        Instant now = clock.instant();
        Transition transition;
        if (decision == ApprovalDecision.APPROVE) {
            Customer customer = customers.findById(context.tenantId(), new CustomerId(message.customerId()))
                    .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
            runSharedGuards(customer, message.contactId(), message.action(), message.templateIntent(),
                    message.body(), message.locale(), Optional.empty());
            if (messages.findOpenByCustomer(tenantId, message.customerId(), Optional.of(message.id())).isPresent()) {
                throw refuse(MessagingErrorCode.MESSAGE_ALREADY_OPEN);
            }
            transition = message.approve(context.membershipId().value(), now);
        } else {
            transition = message.reject(context.membershipId().value(), now);
        }
        OutboundMessage stored = messages.update(message, transition.next());
        MessageApproval approval = messages.insertApproval(new MessageApproval(UUID.randomUUID(), tenantId,
                message.id(), decision, input.note(), now, context.membershipId().value(),
                context.identityId().value()));
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
        return new CommandResult(stored, Optional.of(approval), true);
    }

    /**
     * T1 guard steps 3 to 9 (customer, contact policy, eligibility, policy version when given, body, template),
     * shared with T2 which skips the policy version check.
     */
    private void runSharedGuards(Customer customer, UUID contactId, SemanticAction action,
            SemanticTemplateIntent intent, String body, String locale, Optional<Long> policyVersionIfMatch) {
        if (customer.status() != CustomerStatus.ACTIVE) {
            throw refuse(MessagingErrorCode.CUSTOMER_ARCHIVED);
        }
        ContactPolicy policy = contactPolicies.find(customer);
        if (policy.doNotContact()) {
            throw refuse(MessagingErrorCode.DO_NOT_CONTACT);
        }
        boolean granted = policy.consents().stream().anyMatch(consent -> consent.contactId().equals(contactId)
                && consent.channel() == ContactChannel.WHATSAPP && consent.status() == ConsentStatus.GRANTED);
        if (!granted) {
            throw refuse(MessagingErrorCode.NO_CONTACT_CONSENT);
        }
        var snapshot = followUps.evaluateSnapshot(customer.id());
        FollowUpStatus status = snapshot.evaluation().status();
        if (status != FollowUpStatus.DUE && status != FollowUpStatus.OVERDUE) {
            throw refuse(MessagingErrorCode.FOLLOW_UP_INELIGIBLE);
        }
        if (policyVersionIfMatch.isPresent() && policyVersionIfMatch.get() != snapshot.policyVersion()) {
            throw refuse(MessagingErrorCode.STALE_VERSION);
        }
        var context = grounding.assemble(customer.id())
                .orElseThrow(() -> refuse(MessagingErrorCode.FOLLOW_UP_INELIGIBLE));
        MessageDraft draft;
        try {
            draft = new MessageDraft(action, intent, body, DraftVariables.empty(), locale, List.of(), List.of(),
                    OPERATOR_RATIONALE, RecommendationConfidence.of(1.0));
        } catch (RecommendationValidationException exception) {
            throw refuse(MessagingErrorCode.BODY_REJECTED);
        }
        if (DraftSafetyValidator.validate(draft, context.allowedContextText(), context.grounding()).isPresent()) {
            throw refuse(MessagingErrorCode.BODY_REJECTED);
        }
        if (templates.check(intent) != TemplateMappingStatus.MAPPED) {
            throw refuse(MessagingErrorCode.TEMPLATE_NOT_MAPPED);
        }
    }

    private Customer lockCustomer(TenantContext context, UUID customerId, TenantPermission permission) {
        Customer customer = customers.findByIdForUpdate(context.tenantId(), new CustomerId(customerId))
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        try {
            authorization.requireResourceAccess(permission, customer);
        } catch (TenantAccessDeniedException exception) {
            throw refuse(MessagingErrorCode.FORBIDDEN);
        }
        return customer;
    }

    /** Customer lock, then message lock, then the If-Match check (T2 to T5 guard steps 1 and 2). */
    private OutboundMessage lockMessage(TenantContext context, UUID messageId, long ifMatch) {
        UUID tenantId = context.tenantId().value();
        OutboundMessage unlocked = messages.findById(tenantId, messageId)
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        customers.findByIdForUpdate(context.tenantId(), new CustomerId(unlocked.customerId()))
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        OutboundMessage message = messages.findByIdForUpdate(tenantId, messageId)
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        if (message.version() != ifMatch) {
            throw refuse(MessagingErrorCode.STALE_VERSION);
        }
        return message;
    }

    private CommandResult replay(MessageIdempotencyStore.IdempotencyRecord record, String fingerprint,
            UUID targetMessageId) {
        if (!record.requestFingerprint().equals(fingerprint)
                || (targetMessageId != null && !record.messageId().equals(targetMessageId))) {
            throw refuse(MessagingErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        OutboundMessage message = messages.findById(record.tenantId(), record.messageId())
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        Optional<Object> child = switch (record.operation()) {
            case SUBMIT -> Optional.empty();
            case APPROVAL -> messages.findApproval(record.tenantId(), record.recordId()).map(Object.class::cast);
            case CANCELLATION -> messages.findCancellation(record.tenantId(), record.recordId())
                    .map(Object.class::cast);
            case SEND, RESOLUTION -> Optional.empty();
        };
        return new CommandResult(message, child, false);
    }

    private record SubmitInput(SemanticAction action, SemanticTemplateIntent templateIntent, String body,
            String locale, MessageOrigin origin, long ifMatch, String idempotencyKey) {
    }

    private record ChildInput(String note, long ifMatch, String idempotencyKey) {
    }

    private SubmitInput validate(SubmitMessageCommand command) {
        Objects.requireNonNull(command, "Command is required");
        if (command.customerId() == null || command.contactId() == null || command.body() == null
                || command.body().isBlank()
                || command.body().codePointCount(0, command.body().length()) > OutboundMessage.MAX_BODY_LENGTH
                || !MessagingLocales.supported(command.locale())) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        SemanticAction action = parse(SemanticAction.class, command.action());
        SemanticTemplateIntent intent = parse(SemanticTemplateIntent.class, command.templateIntent());
        MessageOrigin origin = parse(MessageOrigin.class, command.origin());
        return new SubmitInput(action, intent, command.body(), command.locale(), origin,
                ifMatch(command.ifMatch()), idempotencyKey(command.idempotencyKey()));
    }

    private ChildInput validateChild(UUID messageId, String note, String ifMatch, String idempotencyKey) {
        if (messageId == null || (note != null && note.length() > MAX_NOTE_LENGTH)) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        return new ChildInput(note, ifMatch(ifMatch), idempotencyKey(idempotencyKey));
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
