// ABOUTME: Raw cancel inputs; the permission required depends on the message state after the lock.
// ABOUTME: The note is stored on the cancellation row only.
package io.github.stevdrey.dokene.messaging.application;

import java.util.UUID;

public record CancellationCommand(UUID messageId, String note, String ifMatch, String idempotencyKey) {
}
