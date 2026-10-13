package io.github.stevdrey.dokene.followup.application;

import java.util.Objects;

/**
 * Raised by the pre-retry guard when the customer stopped being eligible (archived, do-not-contact, consent revoked,
 * state drift) after the first provider attempt was sent. The retry is abandoned before customer context is
 * transmitted again. Carries only the closed rejection reason: no message, cause or stack trace.
 */
public final class RetryNoLongerEligibleException extends RuntimeException {
    private final transient ActionGateRejectionReason reason;

    public RetryNoLongerEligibleException(ActionGateRejectionReason reason) {
        super("Customer is no longer eligible for a retry", null, false, false);
        this.reason = Objects.requireNonNull(reason, "Reason is required");
    }

    public ActionGateRejectionReason reason() {
        return reason;
    }
}
