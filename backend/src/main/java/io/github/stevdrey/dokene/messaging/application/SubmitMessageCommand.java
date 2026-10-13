// ABOUTME: Raw submit inputs as feature 2 receives them; the service validates and parses every field.
// ABOUTME: Enum names and the key arrive raw; ifMatch is the version the API layer parsed from the strong ETag, as digits.
package io.github.stevdrey.dokene.messaging.application;

import java.util.UUID;

public record SubmitMessageCommand(UUID customerId, UUID contactId, String action, String templateIntent,
        String body, String locale, String origin, String ifMatch, String idempotencyKey) {
}
