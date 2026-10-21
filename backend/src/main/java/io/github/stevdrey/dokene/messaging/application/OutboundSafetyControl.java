// ABOUTME: Port the integration module implements for the sender identity and send policy steps 3 and 4 (ADR 0024 §3).
// ABOUTME: Fails closed: anything but Permitted blocks the send; the configured sender is readable while sending is off.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public interface OutboundSafetyControl {
    /**
     * The configured integration's sending identity, independent of both kill switches and the enabled flag, so an
     * approval (T2) can bind it while sending is switched off. Empty only when the tenant has no integration.
     */
    Optional<ApprovalFingerprint.SenderRevision> configuredSender(TenantId tenantId);

    /**
     * Send policy steps 3 and 4 in order: the global switch, the tenant switch, then the integration state. The gate
     * that calls this does not check either switch itself, so every implementation enforces both. Read at send time,
     * never cached across requests; implementations throw rather than guess on missing state.
     */
    Decision evaluate(TenantId tenantId);

    sealed interface Decision permits Permitted, Blocked {
    }

    /** Both kill switches are on and the integration is enabled; the sender is the one the message would use. */
    record Permitted(ApprovalFingerprint.SenderRevision sender) implements Decision {
        public Permitted {
            Objects.requireNonNull(sender, "Sender is required");
        }
    }

    /** The send is refused with one of the kill-switch or integration codes, nothing else. */
    record Blocked(MessagingErrorCode code) implements Decision {
        public static final Set<MessagingErrorCode> CODES = Set.of(MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY,
                MessagingErrorCode.OUTBOUND_DISABLED_FOR_TENANT, MessagingErrorCode.INTEGRATION_DISABLED);

        public Blocked {
            Objects.requireNonNull(code, "Code is required");
            if (!CODES.contains(code)) {
                throw new IllegalArgumentException("Not an outbound safety code: " + code);
            }
        }
    }
}
