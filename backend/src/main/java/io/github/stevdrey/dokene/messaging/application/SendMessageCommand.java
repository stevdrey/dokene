// ABOUTME: A manual send request (T6 onwards): the message, the version the operator saw and the idempotency key.
// ABOUTME: Raw transport values; OutboundDispatchService validates them before touching anything.
package io.github.stevdrey.dokene.messaging.application;

import java.util.UUID;

public record SendMessageCommand(UUID messageId, String ifMatch, String idempotencyKey) {
}
