// ABOUTME: Raw T12 inputs: a member declares an unknown send outcome FAILED after checking the provider side.
// ABOUTME: outcome is typed; null is invalid input. ifMatch is the parsed version, as digits.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.ResolutionOutcome;
import java.util.UUID;

public record ResolutionCommand(UUID messageId, ResolutionOutcome outcome, String note, String ifMatch,
        String idempotencyKey) {
}
