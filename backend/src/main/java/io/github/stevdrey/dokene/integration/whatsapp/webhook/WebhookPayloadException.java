// ABOUTME: A verified webhook body that is not a Meta delivery callback; the route answers 400 whatever the reason.
// ABOUTME: Carries a closed reason and no cause, so no payload text can reach a log line or an error body.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import java.util.Objects;

public final class WebhookPayloadException extends RuntimeException {
    private final Reason reason;

    public WebhookPayloadException(Reason reason) {
        super("Webhook payload rejected: reason=" + Objects.requireNonNull(reason, "Reason is required"), null, false,
                false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason { MALFORMED_JSON, SCHEMA, UNEXPECTED_MEMBER, TOO_MANY_STATUSES }
}
