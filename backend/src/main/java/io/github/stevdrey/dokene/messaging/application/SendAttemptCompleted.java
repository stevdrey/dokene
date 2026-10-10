// ABOUTME: Application event published inside send phase 3, so AFTER_COMMIT listeners see only committed attempts.
// ABOUTME: Carries ids and the outcome only; adapters that need more look the message up under their own context.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.SendAttemptOutcome;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SendAttemptCompleted(UUID tenantId, UUID messageId, Optional<String> providerMessageId,
        SendAttemptOutcome outcome) {
    public SendAttemptCompleted {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(messageId, "Message id is required");
        Objects.requireNonNull(providerMessageId, "Provider message id option is required");
        Objects.requireNonNull(outcome, "Outcome is required");
    }
}
