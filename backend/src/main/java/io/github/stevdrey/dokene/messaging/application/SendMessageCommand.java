// ABOUTME: Raw send inputs from the HTTP boundary: the message, its If-Match version and the idempotency key.
// ABOUTME: No body: the message carries everything the provider needs.
package io.github.stevdrey.dokene.messaging.application;

import java.util.UUID;

public record SendMessageCommand(UUID messageId, String ifMatch, String idempotencyKey) {
}
