// ABOUTME: Raw submit inputs as feature 2 receives them; the service validates and parses every field.
// ABOUTME: Enum names, If-Match and the idempotency key arrive as strings so INVALID_INPUT is decided here.
package io.github.stevdrey.dokene.messaging.application;

import java.util.UUID;

public record SubmitMessageCommand(UUID customerId, UUID contactId, String action, String templateIntent,
        String body, String locale, String origin, String ifMatch, String idempotencyKey) {
}
