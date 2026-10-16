// ABOUTME: The last-moment send policy (ADR 0023 §4.3, ADR 0024 §2 and §5, issue 138), run under the customer and
// ABOUTME: message locks in the transaction that commits the attempt row. Fails closed: a refusal calls no provider.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Every input is re-read from authoritative state, never from the request: kill switches and integration, the stored
 * approval, the customer's contact policy and eligibility, the contact frequency and tenant rate bounds, the current
 * template mapping and the approval fingerprint. The command it returns carries exactly the parameter values of the
 * binding whose fingerprint just matched, so what is sent is what was approved.
 */
@Component
@EnableConfigurationProperties(MessagingDispatchProperties.class)
public class SendPolicyGate {
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final OutboundMessageRepository messages;
    private final MessageSendGuards guards;
    private final ApprovalBindingAssembler bindings;
    private final OutboundSafetyControl safety;
    private final MessagingDispatchProperties properties;

    public SendPolicyGate(OutboundMessageRepository messages, MessageSendGuards guards,
            ApprovalBindingAssembler bindings, OutboundSafetyControl safety, MessagingDispatchProperties properties) {
        this.messages = Objects.requireNonNull(messages);
        this.guards = Objects.requireNonNull(guards);
        this.bindings = Objects.requireNonNull(bindings);
        this.safety = Objects.requireNonNull(safety);
        this.properties = Objects.requireNonNull(properties);
    }

    public int maxSendAttempts() {
        return properties.maxSendAttempts();
    }

    /**
     * Steps 3 to 11 in ADR order; steps 1 and 2 (context and {@code MESSAGE_SEND}) are the caller's, which resolved
     * {@code context} from an active membership. The caller holds the customer then the message row lock.
     */
    public OutboundSendCommand evaluate(TenantContext context, Customer customer, OutboundMessage message,
            Instant now) {
        Objects.requireNonNull(context, "Tenant context is required");
        Objects.requireNonNull(customer, "Customer is required");
        Objects.requireNonNull(message, "Message is required");
        Objects.requireNonNull(now, "Time is required");
        if (!message.tenantId().equals(context.tenantId().value())
                || !message.customerId().equals(customer.id().value())) {
            throw new IllegalArgumentException("Message, customer and context must belong together");
        }

        // Steps 3 and 4: both kill switches, then the enabled, ownership-verified integration and its sender.
        ApprovalFingerprint.SenderRevision sender = switch (safety.evaluate(context.tenantId())) {
            case OutboundSafetyControl.Permitted permitted -> permitted.sender();
            case OutboundSafetyControl.Blocked blocked -> throw refuse(blocked.code());
        };

        // Step 5: an APPROVED message with an approval that bound a fingerprint.
        if (message.status() != MessageStatus.APPROVED) {
            throw refuse(MessagingErrorCode.INVALID_TRANSITION);
        }
        ApprovalFingerprint approved = messages.findApprovalByMessage(message.tenantId(), message.id())
                .filter(approval -> approval.decision() == ApprovalDecision.APPROVE)
                .flatMap(MessageApproval::fingerprint)
                .orElseThrow(() -> refuse(MessagingErrorCode.APPROVAL_INVALIDATED));

        // Steps 6, 7 and 11: contact still owned, customer active, no do-not-contact, consent, due, body.
        if (customer.phones().stream().noneMatch(phone -> phone.id().equals(message.contactId()))) {
            throw refuse(MessagingErrorCode.CONTACT_NOT_OWNED);
        }
        guards.check(customer, message.contactId(), message.action(), message.templateIntent(), message.body(),
                message.locale(), Optional.empty());

        // Step 8 and the ADR 0024 §5 abuse bounds: contact frequency, then the tenant's rate of started attempts.
        Duration interval = properties.minContactInterval();
        if (!interval.isZero()
                && messages.sentToCustomerSince(message.tenantId(), message.customerId(), now.minus(interval))) {
            throw refuse(MessagingErrorCode.CONTACT_FREQUENCY_EXCEEDED);
        }
        messages.acquireSendBudgetLock(message.tenantId());
        if (messages.countAttemptsStartedSince(message.tenantId(), now.minus(RATE_WINDOW))
                >= properties.sendRatePerTenantPerMinute()) {
            throw refuse(MessagingErrorCode.RATE_LIMITED);
        }

        // Steps 9 and 5b: re-resolve and render the template now, then require the approval to cover that binding.
        ApprovalFingerprint.Binding binding = bindings.bind(message, customer, sender);
        if (!approved.covers(binding)) {
            throw refuse(MessagingErrorCode.APPROVAL_INVALIDATED);
        }

        // Step 10.
        if (message.attemptCount() >= properties.maxSendAttempts()) {
            throw refuse(MessagingErrorCode.SEND_ATTEMPTS_EXHAUSTED);
        }
        return new OutboundSendCommand(message.recipientPhone(), binding.template().providerTemplateName(),
                binding.template().language(), binding.parameterValues(), message.locale(), message.sendKey(),
                sender);
    }

    private static MessagingRefusedException refuse(MessagingErrorCode code) {
        return new MessagingRefusedException(code);
    }
}
