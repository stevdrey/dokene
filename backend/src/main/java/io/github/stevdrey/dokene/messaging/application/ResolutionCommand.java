// ABOUTME: Raw T12 inputs: a member declares an unknown send outcome FAILED after checking the provider side.
// ABOUTME: outcome is the contract value's name; only FAILED is accepted. ifMatch is the parsed version, as digits.
package io.github.stevdrey.dokene.messaging.application;

import java.util.UUID;

public record ResolutionCommand(UUID messageId, String outcome, String note, String ifMatch, String idempotencyKey) {
}
