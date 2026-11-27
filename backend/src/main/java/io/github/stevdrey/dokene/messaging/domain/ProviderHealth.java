// ABOUTME: One provider_health row as a value (ADR 0038): the stored state, the outage and recovery run counters, the
// ABOUTME: cool down and the probe lease. It holds no tenant, message, phone or credential data.
package io.github.stevdrey.dokene.messaging.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Nullable members mirror nullable columns: {@code lastSignalAt} and {@code lastSignalKind} until the first signal,
 * {@code firstOutageSignalAt} while no outage run is open, {@code coolDownUntil} outside {@code UNAVAILABLE}, and
 * {@code probeLeaseUntil} while no probe holds the lease.
 */
public record ProviderHealth(MessagingProviderKey provider, ProviderHealthState state, Instant stateChangedAt,
        Instant lastSignalAt, ProviderSignalKind lastSignalKind, int consecutiveOutageSignals,
        Instant firstOutageSignalAt, int consecutiveSuccesses, Instant coolDownUntil, int coolDownStep,
        Instant probeLeaseUntil) {

    public ProviderHealth {
        Objects.requireNonNull(provider, "Provider is required");
        Objects.requireNonNull(state, "State is required");
        Objects.requireNonNull(stateChangedAt, "State change time is required");
        if (consecutiveOutageSignals < 0 || consecutiveSuccesses < 0 || coolDownStep < 0) {
            throw new IllegalArgumentException("Health counters cannot be negative");
        }
    }

    /** The seed row: nothing is known until a probe or, in observe mode, a send says so. */
    public static ProviderHealth unknown(MessagingProviderKey provider, Instant since) {
        return new ProviderHealth(provider, ProviderHealthState.UNKNOWN, since, null, null, 0, null, 0, null, 0,
                null);
    }

    ProviderHealth withState(ProviderHealthState next, Instant now) {
        return next == state ? this : new ProviderHealth(provider, next, now, lastSignalAt, lastSignalKind,
                consecutiveOutageSignals, firstOutageSignalAt, consecutiveSuccesses, coolDownUntil, coolDownStep,
                probeLeaseUntil);
    }

    ProviderHealth withLastSignal(ProviderSignalKind kind, Instant now) {
        return new ProviderHealth(provider, state, stateChangedAt, now, kind, consecutiveOutageSignals,
                firstOutageSignalAt, consecutiveSuccesses, coolDownUntil, coolDownStep, probeLeaseUntil);
    }

    ProviderHealth withOutageRun(int count, Instant firstAt) {
        return new ProviderHealth(provider, state, stateChangedAt, lastSignalAt, lastSignalKind, count, firstAt,
                consecutiveSuccesses, coolDownUntil, coolDownStep, probeLeaseUntil);
    }

    ProviderHealth withSuccesses(int count) {
        return new ProviderHealth(provider, state, stateChangedAt, lastSignalAt, lastSignalKind,
                consecutiveOutageSignals, firstOutageSignalAt, count, coolDownUntil, coolDownStep, probeLeaseUntil);
    }

    ProviderHealth withCoolDown(Instant until, int step) {
        return new ProviderHealth(provider, state, stateChangedAt, lastSignalAt, lastSignalKind,
                consecutiveOutageSignals, firstOutageSignalAt, consecutiveSuccesses, until, step, probeLeaseUntil);
    }

    ProviderHealth withProbeLease(Instant until) {
        return new ProviderHealth(provider, state, stateChangedAt, lastSignalAt, lastSignalKind,
                consecutiveOutageSignals, firstOutageSignalAt, consecutiveSuccesses, coolDownUntil, coolDownStep,
                until);
    }
}
