// ABOUTME: What a delivery report did to a message: applied a transition, or was recorded and ignored.
// ABOUTME: An ignored report still produces an event row and bumps the version.
package io.github.stevdrey.dokene.messaging.domain;

import java.util.Objects;

public sealed interface DeliveryOutcome {

    record Applied(Transition transition) implements DeliveryOutcome {
        public Applied {
            Objects.requireNonNull(transition, "Transition is required");
        }
    }

    record Ignored(OutboundMessage next, MessageEvent event) implements DeliveryOutcome {
        public Ignored {
            Objects.requireNonNull(next, "Next state is required");
            Objects.requireNonNull(event, "Event is required");
            if (event.applied() || event.sequenceNumber() != next.version()) {
                throw new IllegalArgumentException("Ignored outcomes carry a non applied event at the next version");
            }
        }
    }
}
