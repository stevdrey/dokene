package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Port for receiving notification of security-relevant AI Action Gate rejections.
 * Payloads contain only privacy-safe identifiers and closed diagnostic codes.
 * Prompts, raw responses, and customer notes are never accepted.
 */
@FunctionalInterface
public interface AiActionGateAuditListener {

    void onSecurityRejection(CustomerId customerId, ActionGateRejectionReason reason, String diagnosticCode);

    static AiActionGateAuditListener logging() {
        Logger log = LoggerFactory.getLogger(AiActionGateAuditListener.class);
        return (customerId, reason, diagnosticCode) -> {
            log.warn("AI Action Gate rejected advisory recommendation: customerId={}, reason={}, diagnosticCode={}",
                    customerId != null ? customerId.value() : null, reason, diagnosticCode);
        };
    }

    static AiActionGateAuditListener noop() {
        return (customerId, reason, diagnosticCode) -> { };
    }
}
