// ABOUTME: Storage port behind the provider health gate (ADR 0038): the row with the database time, the probe lease
// ABOUTME: and the probe result. Each write is its own short transaction, and a failure is the caller's to handle.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealth;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy.RefusalReason;
import io.github.stevdrey.dokene.messaging.domain.ProviderProbeResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public interface ProviderHealthStore {

    /** The stored row and the database's own time in the statement that read it. */
    record Snapshot(ProviderHealth row, Instant now) {
        public Snapshot {
            Objects.requireNonNull(row, "Health row is required");
            Objects.requireNonNull(now, "Database time is required");
        }
    }

    /** What an attempt to take the probe lease found once it held the row lock. */
    sealed interface LeaseAttempt {
        /** The lease is this caller's. {@code token} is the stored lease end, which a result must still match. */
        record Taken(Instant token) implements LeaseAttempt {
        }

        /** A late signal or another probe already opened the state: no probe is needed. */
        record NotNeeded() implements LeaseAttempt {
        }

        /** Another probe holds the lease, or the cool down is running again. */
        record Busy(RefusalReason reason, Duration retryAfter) implements LeaseAttempt {
        }
    }

    /** What happened to a probe result once the row lock was held. */
    sealed interface ProbeApplication {
        Snapshot snapshot();

        /** The lease still matched, so the result was applied. {@code snapshot} is the row after it. */
        record Applied(Snapshot snapshot) implements ProbeApplication {
        }

        /** The lease was no longer the one the probe started under, so the result was discarded. */
        record Superseded(Snapshot snapshot) implements ProbeApplication {
        }
    }

    /**
     * Reads without locking the row. Safe inside a caller's transaction: a failed read leaves that transaction usable.
     * Throws the store's data access exception when the row cannot be read.
     */
    Snapshot current(MessagingProviderKey provider);

    /** Locks the row, decides again and, when a probe is still due, stores the lease (and a stale row as UNKNOWN). */
    LeaseAttempt takeLease(MessagingProviderKey provider);

    /** Locks the row and applies {@code result} only if {@code token} is still the stored lease. */
    ProbeApplication finishProbe(MessagingProviderKey provider, Instant token, ProviderProbeResult result);
}
