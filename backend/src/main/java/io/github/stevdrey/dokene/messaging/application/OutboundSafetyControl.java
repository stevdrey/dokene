// ABOUTME: Port the integration module implements to answer send policy steps 3 and 4 (ADR 0023 §4.3, ADR 0024 §3).
// ABOUTME: Fails closed: anything but Permitted blocks the send, and Permitted names the sender the approval binds.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;
import java.util.Set;

public interface OutboundSafetyControl {
    /** Read at send time, never cached across requests; implementations throw rather than guess on missing state. */
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
