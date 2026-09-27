package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Port for receiving notification of security-relevant AI Action Gate rejections.
 * Payloads contain only privacy-safe identifiers and closed diagnostic codes.
 * Prompts, raw responses, and customer notes are never accepted.
 */
@FunctionalInterface
public interface AiActionGateAuditListener {

    void onSecurityRejection(SecurityRejectionEvent event);

    record SecurityRejectionEvent(
            TenantId tenantId,
            IdentityId actorId,
            CustomerId customerId,
            ActionGateRejectionReason reason,
            String diagnosticCode,
            Instant timestamp
    ) {
        public SecurityRejectionEvent {
            Objects.requireNonNull(reason, "Rejection reason is required");
            Objects.requireNonNull(diagnosticCode, "Diagnostic code is required");
            Objects.requireNonNull(timestamp, "Timestamp is required");
        }
    }

    static AiActionGateAuditListener logging() {
        Logger log = LoggerFactory.getLogger(AiActionGateAuditListener.class);
        return event -> {
            log.warn("AI Action Gate rejected advisory recommendation: tenantId={}, actorId={}, customerId={}, reason={}, diagnosticCode={}, timestamp={}",
                    event.tenantId() != null ? event.tenantId().value() : null,
                    event.actorId() != null ? event.actorId().value() : null,
                    event.customerId() != null ? event.customerId().value() : null,
                    event.reason(),
                    event.diagnosticCode(),
                    event.timestamp());
        };
    }

    static AiActionGateAuditListener noop() {
        return event -> { };
    }
}
