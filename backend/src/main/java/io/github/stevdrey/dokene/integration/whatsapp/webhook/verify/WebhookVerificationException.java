// ABOUTME: The one refusal shape of the webhook verifier: a closed reason and nothing else (spec 0007 AC-7).
// ABOUTME: Carries no cause and no request data, so its message and toString are safe to log or map to a status.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import java.util.Objects;

public final class WebhookVerificationException extends RuntimeException {
    /** Why the request was refused. The route maps every reason to the same outward status. */
    public enum Reason {
        MISSING_SIGNATURE,
        MALFORMED_SIGNATURE,
        SIGNATURE_MISMATCH,
        BODY_TOO_LARGE,
        SECRET_UNAVAILABLE,
        WRONG_MODE,
        INVALID_CHALLENGE,
        TOKEN_MISMATCH
    }

    private final Reason reason;

    public WebhookVerificationException(Reason reason) {
        super("Webhook rejected: reason=" + Objects.requireNonNull(reason, "Reason is required"));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
