// ABOUTME: Outcome of send phase 1: a replay of an earlier send, or a committed attempt ready for the provider.
// ABOUTME: Sealed so the orchestrator handles both and nothing else.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import java.util.Objects;

public sealed interface AcceptResult {

    record Replayed(CommandResult result) implements AcceptResult {
        public Replayed {
            Objects.requireNonNull(result, "Replay result is required");
        }
    }

    /** The SENDING message and the STARTED attempt as committed, plus the template resolved under the lock. */
    record Accepted(OutboundMessage message, SendAttempt attempt, ResolvedTemplate template) implements AcceptResult {
        public Accepted {
            Objects.requireNonNull(message, "Message is required");
            Objects.requireNonNull(attempt, "Attempt is required");
            Objects.requireNonNull(template, "Template is required");
        }
    }
}
