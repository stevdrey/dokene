// ABOUTME: Resolves and renders the template a not-yet-dispatched message would be sent with, for review (issue 268).
// ABOUTME: Read-only and fail closed: a message that could not be approved as it stands has no preview.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.messaging.application.ApprovalBindingAssembler.ReviewedTemplate;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The preview is rendered from the tenant's current mapping and customer, the records approval and send bind, so it
 * is only shown while the message still awaits approval or dispatch. Once dispatched, the current records may differ
 * from what was sent, so nothing is shown rather than something that may be wrong. It runs inside the caller's tenant
 * context and transaction.
 */
@Component
public class MessageTemplatePreviews {
    private final ApprovalBindingAssembler bindings;
    private final CustomerRepository customers;

    public MessageTemplatePreviews(ApprovalBindingAssembler bindings, CustomerRepository customers) {
        this.bindings = Objects.requireNonNull(bindings, "Binding assembler is required");
        this.customers = Objects.requireNonNull(customers, "Customer repository is required");
    }

    public Optional<ReviewedTemplate> of(OutboundMessage message) {
        Objects.requireNonNull(message, "Message is required");
        if (message.status() != MessageStatus.PENDING_APPROVAL && message.status() != MessageStatus.APPROVED) {
            return Optional.empty();
        }
        return customers.findById(new TenantId(message.tenantId()), new CustomerId(message.customerId()))
                .flatMap(customer -> bindings.review(message, customer));
    }
}
