package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.customer.domain.CustomerId;

/**
 * Outbound port for privacy-safe, durable AI lifecycle audit. Implemented by the audit module so the follow-up
 * module keeps a one-way dependency. Arguments are closed vocabularies only; adapters must never receive
 * prompts, generated text, customer notes, phone numbers or provider messages.
 */
public interface AiOutcomeAuditPort {
    void generated(CustomerId customerId, AiOperation operation);

    void modelRefused(CustomerId customerId, AiOperation operation);

    void gateRejected(CustomerId customerId, AiOperation operation, ActionGateRejectionReason reason);

    void failed(CustomerId customerId, AiOperation operation, AiUnavailableReason reason);

    static AiOutcomeAuditPort noop() {
        return new AiOutcomeAuditPort() {
            @Override
            public void generated(CustomerId customerId, AiOperation operation) {
            }

            @Override
            public void modelRefused(CustomerId customerId, AiOperation operation) {
            }

            @Override
            public void gateRejected(CustomerId customerId, AiOperation operation, ActionGateRejectionReason reason) {
            }

            @Override
            public void failed(CustomerId customerId, AiOperation operation, AiUnavailableReason reason) {
            }
        };
    }
}
