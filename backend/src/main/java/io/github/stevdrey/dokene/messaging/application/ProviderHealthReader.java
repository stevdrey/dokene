// ABOUTME: Read port for provider health (ADR 0038), for the state gauge and the operational read model.
// ABOUTME: It returns the effective state, so a row with no recent signal already reads as UNKNOWN.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthState;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public interface ProviderHealthReader {

    /** {@code stateChangedAt} is when the stored state last changed; {@code lastSignalAt} is empty before the first. */
    record ProviderHealthStatus(ProviderHealthState state, Instant stateChangedAt, Optional<Instant> lastSignalAt) {
        public ProviderHealthStatus {
            Objects.requireNonNull(state, "State is required");
            Objects.requireNonNull(stateChangedAt, "State change time is required");
            Objects.requireNonNull(lastSignalAt, "Last signal option is required");
        }
    }

    /** Throws the store's data access exception when the row cannot be read; the caller decides what that means. */
    ProviderHealthStatus read(MessagingProviderKey provider);
}
