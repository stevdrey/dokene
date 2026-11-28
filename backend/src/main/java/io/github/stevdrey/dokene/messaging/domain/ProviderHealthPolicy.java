// ABOUTME: The one pure function behind provider health (ADR 0038): current row, event, time and settings give the
// ABOUTME: next row, and row, time and settings give the send decision. No clock, no storage, no other writer of state.
package io.github.stevdrey.dokene.messaging.domain;

import io.github.stevdrey.dokene.messaging.domain.ProviderHealthEvent.ProbeFinished;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthEvent.SendSignal;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthEvent.WentStale;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthSettings.Mode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ProviderHealthPolicy {
    private final ProviderHealthSettings settings;

    public ProviderHealthPolicy(ProviderHealthSettings settings) {
        this.settings = Objects.requireNonNull(settings, "Settings are required");
    }

    /** What the gate does with a send that reached it. */
    public sealed interface Decision {
        record Allow() implements Decision {
        }

        record Refuse(RefusalReason reason, Duration retryAfter) implements Decision {
        }

        record Probe() implements Decision {
        }
    }

    /**
     * Why a send was refused, a closed set for meters. {@link #decide} gives only the first two; the gate adds the
     * rest once a probe ran or the store could not be used.
     */
    public enum RefusalReason {
        COOL_DOWN, PROBE_IN_FLIGHT, PROBE_FAILED, NO_CREDENTIAL, REPROBE, STORE_UNAVAILABLE
    }

    /** The row after {@code event} at {@code now}. Every state change in the system is a result of this method. */
    public ProviderHealth apply(ProviderHealth row, ProviderHealthEvent event, Instant now) {
        Objects.requireNonNull(row, "Health row is required");
        Objects.requireNonNull(now, "Time is required");
        return switch (event) {
            case SendSignal signal -> onSignal(row, signal.kind(), now);
            case ProbeFinished probe -> onProbe(row, probe.result(), now);
            case WentStale _ -> stale(row, now) ? row.withState(ProviderHealthState.UNKNOWN, now) : row;
        };
    }

    /** The stored state, except that a HEALTHY or DEGRADED row with no signal for {@code staleAfter} is UNKNOWN. */
    public ProviderHealthState effectiveState(ProviderHealth row, Instant now) {
        return stale(row, now) ? ProviderHealthState.UNKNOWN : row.state();
    }

    public Decision decide(ProviderHealth row, Instant now) {
        ProviderHealthState state = effectiveState(row, now);
        if (state == ProviderHealthState.HEALTHY || state == ProviderHealthState.DEGRADED) {
            return new Decision.Allow();
        }
        if (state == ProviderHealthState.UNAVAILABLE && row.coolDownUntil() != null
                && now.isBefore(row.coolDownUntil())) {
            return new Decision.Refuse(RefusalReason.COOL_DOWN, wholeSecondsUntil(row.coolDownUntil(), now));
        }
        if (row.probeLeaseUntil() != null && now.isBefore(row.probeLeaseUntil())) {
            return new Decision.Refuse(RefusalReason.PROBE_IN_FLIGHT,
                    wholeSecondsUntil(row.probeLeaseUntil(), now));
        }
        return new Decision.Probe();
    }

    /**
     * The row a request holds while it probes. A row that went stale becomes UNKNOWN first, and the lease runs until
     * {@code now} plus {@code probeLease}. That exact time is also the lease token: a probe result applies only while
     * the row still stores it.
     */
    public ProviderHealth lease(ProviderHealth row, Instant now) {
        return apply(row, new WentStale(), now).withProbeLease(now.plus(settings.probeLease()));
    }

    /**
     * How long a refused sender waits when no fresh decision says so: the cool down left, else the lease time left,
     * else one second. After a failed probe this is the whole cool down that probe just started.
     */
    public Duration retryAfter(ProviderHealth row, Instant now) {
        if (row.state() == ProviderHealthState.UNAVAILABLE && row.coolDownUntil() != null
                && now.isBefore(row.coolDownUntil())) {
            return wholeSecondsUntil(row.coolDownUntil(), now);
        }
        if (row.probeLeaseUntil() != null && now.isBefore(row.probeLeaseUntil())) {
            return wholeSecondsUntil(row.probeLeaseUntil(), now);
        }
        return Duration.ofSeconds(1);
    }

    /** The cool down a trip or failed probe starts when the row's step is {@code step}. */
    public Duration coolDownLength(int step) {
        Duration length = settings.coolDownInitial();
        for (int doubled = 0; doubled < step && length.compareTo(settings.coolDownMax()) < 0; doubled++) {
            length = length.multipliedBy(2);
        }
        return length.compareTo(settings.coolDownMax()) > 0 ? settings.coolDownMax() : length;
    }

    private ProviderHealth onSignal(ProviderHealth row, ProviderSignalKind kind, Instant now) {
        boolean closed = row.state() == ProviderHealthState.UNAVAILABLE || row.state() == ProviderHealthState.UNKNOWN;
        if (closed && settings.mode() == Mode.OBSERVE) {
            // No probe runs in observe mode, so a send result stands in for one.
            return onProbe(row, switch (kind) {
                case SUCCESS -> ProviderProbeResult.PASSED;
                case THROTTLED -> ProviderProbeResult.PASSED_THROTTLED;
                case OUTAGE -> ProviderProbeResult.FAILED;
            }, now);
        }
        ProviderHealth touched = row.withLastSignal(kind, now);
        if (closed) {
            // The late result of a send that was let through before the state closed: only a probe may reopen it.
            return touched;
        }
        return switch (kind) {
            case SUCCESS -> onSuccess(touched, now);
            case THROTTLED -> touched.withSuccesses(0).withState(ProviderHealthState.DEGRADED, now);
            case OUTAGE -> onOutage(touched, now);
        };
    }

    private ProviderHealth onSuccess(ProviderHealth row, Instant now) {
        if (row.state() == ProviderHealthState.HEALTHY) {
            return row.withCoolDown(null, 0);
        }
        ProviderHealth counted = row.withOutageRun(0, null).withSuccesses(row.consecutiveSuccesses() + 1);
        return counted.consecutiveSuccesses() >= settings.recoverySuccesses()
                ? counted.withCoolDown(null, 0).withState(ProviderHealthState.HEALTHY, now)
                : counted;
    }

    private ProviderHealth onOutage(ProviderHealth row, Instant now) {
        boolean runOpen = row.firstOutageSignalAt() != null
                && now.isBefore(row.firstOutageSignalAt().plus(settings.outageWindow()));
        ProviderHealth counted = (runOpen
                ? row.withOutageRun(row.consecutiveOutageSignals() + 1, row.firstOutageSignalAt())
                : row.withOutageRun(1, now)).withSuccesses(0);
        return counted.consecutiveOutageSignals() >= settings.outageThreshold()
                ? trip(counted, now)
                : counted.withState(ProviderHealthState.DEGRADED, now);
    }

    private ProviderHealth onProbe(ProviderHealth row, ProviderProbeResult result, Instant now) {
        ProviderHealth released = row.withProbeLease(null);
        boolean open = row.state() == ProviderHealthState.HEALTHY || row.state() == ProviderHealthState.DEGRADED;
        if (result == ProviderProbeResult.NO_RESULT || open) {
            // No exchange says nothing, and a late or overlapping probe never overrides what sends have shown since.
            return released;
        }
        boolean fromUnknown = row.state() == ProviderHealthState.UNKNOWN;
        return switch (result) {
            case FAILED -> trip(released.withLastSignal(ProviderSignalKind.OUTAGE, now), now);
            case PASSED_THROTTLED -> reopened(released.withLastSignal(ProviderSignalKind.THROTTLED, now), 0)
                    .withState(ProviderHealthState.DEGRADED, now);
            case PASSED -> {
                ProviderHealth passed = released.withLastSignal(ProviderSignalKind.SUCCESS, now);
                // From UNKNOWN nothing bad was seen, so one pass is enough. From UNAVAILABLE it is the first success
                // of the recovery run and the back off step is kept until HEALTHY.
                yield fromUnknown || settings.recoverySuccesses() <= 1
                        ? reopened(passed, 0).withCoolDown(null, 0).withState(ProviderHealthState.HEALTHY, now)
                        : reopened(passed, 1).withState(ProviderHealthState.DEGRADED, now);
            }
            case NO_RESULT -> released;
        };
    }

    private ProviderHealth reopened(ProviderHealth row, int successes) {
        return row.withOutageRun(0, null).withSuccesses(successes).withCoolDown(null, row.coolDownStep());
    }

    /** The step is not reset first, so a trip soon after a passed probe backs off further. */
    private ProviderHealth trip(ProviderHealth row, Instant now) {
        int step = row.coolDownStep();
        boolean capped = coolDownLength(step).compareTo(settings.coolDownMax()) >= 0;
        return row.withCoolDown(now.plus(coolDownLength(step)), capped ? step : step + 1)
                .withState(ProviderHealthState.UNAVAILABLE, now);
    }

    private boolean stale(ProviderHealth row, Instant now) {
        if (row.state() != ProviderHealthState.HEALTHY && row.state() != ProviderHealthState.DEGRADED) {
            return false;
        }
        Instant last = row.lastSignalAt() != null ? row.lastSignalAt() : row.stateChangedAt();
        return !now.isBefore(last.plus(settings.staleAfter()));
    }

    private static Duration wholeSecondsUntil(Instant until, Instant now) {
        Duration left = Duration.between(now, until);
        long seconds = left.getSeconds() + (left.getNano() > 0 ? 1 : 0);
        return Duration.ofSeconds(Math.max(1, seconds));
    }
}
