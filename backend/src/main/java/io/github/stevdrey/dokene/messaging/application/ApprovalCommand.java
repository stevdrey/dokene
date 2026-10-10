// ABOUTME: Raw approve or reject inputs; the decision is the service method called.
// ABOUTME: The note is stored on the approval row only.
package io.github.stevdrey.dokene.messaging.application;

import java.util.UUID;

public record ApprovalCommand(UUID messageId, String note, String ifMatch, String idempotencyKey) {
}
