// ABOUTME: Resolves and renders the template a not-yet-dispatched message would be sent with, for review (issue 268).
// ABOUTME: Read-only and fail closed: no preview for a message that could not be approved, or an approval not covering it.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.messaging.application.ApprovalBindingAssembler.ReviewedTemplate;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The preview is rendered from the tenant's current mapping and customer, the records approval and send bind, so it
 * is only shown while the message still awaits approval or dispatch. Once dispatched, the current records may differ
 * from what was sent, so nothing is shown rather than something that may be wrong. An approved message shows the
 * preview only while its approval still covers what would be sent, the check the send gate repeats: content changed
 * after approval was never approved and cannot be sent, so it is not presented as awaiting dispatch. It runs inside the
 * caller's tenant context and transaction.
 */
@Component
public class MessageTemplatePreviews {
    private final ApprovalBindingAssembler bindings;
    private final CustomerRepository customers;
    private final OutboundSafetyControl safety;

    public MessageTemplatePreviews(ApprovalBindingAssembler bindings, CustomerRepository customers,
            OutboundSafetyControl safety) {
        this.bindings = Objects.requireNonNull(bindings, "Binding assembler is required");
        this.customers = Objects.requireNonNull(customers, "Customer repository is required");
        this.safety = Objects.requireNonNull(safety, "Safety control is required");
    }

    /** {@code approval} is the message's decision as read in the same snapshot, empty while none was made. */
    public Optional<ReviewedTemplate> of(OutboundMessage message, Optional<MessageApproval> approval) {
        Objects.requireNonNull(message, "Message is required");
        Objects.requireNonNull(approval, "Approval is required");
        if (message.status() != MessageStatus.PENDING_APPROVAL && message.status() != MessageStatus.APPROVED) {
            return Optional.empty();
        }
        return customers.findById(new TenantId(message.tenantId()), new CustomerId(message.customerId()))
                .filter(customer -> message.status() != MessageStatus.APPROVED || covered(message, customer, approval))
                .flatMap(customer -> bindings.review(message, customer));
    }

    /** Binds the message exactly as the send gate does, against the configured sender approval bound. */
    private boolean covered(OutboundMessage message, Customer customer, Optional<MessageApproval> approval) {
        Optional<ApprovalFingerprint> approved = approval
                .filter(decision -> decision.decision() == ApprovalDecision.APPROVE)
                .flatMap(MessageApproval::fingerprint);
        Optional<ApprovalFingerprint.SenderRevision> sender = safety.configuredSender(new TenantId(message.tenantId()));
        if (approved.isEmpty() || sender.isEmpty()) {
            return false;
        }
        try {
            return approved.get().covers(bindings.bind(message, customer, sender.get()));
        } catch (MessagingRefusedException refused) {
            return false;
        }
    }
}
