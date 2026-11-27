// ABOUTME: Validated thresholds and timings of provider health tracking (ADR 0038), the settings input of
// ABOUTME: ProviderHealthPolicy. Every bound is checked here, so a bad value stops startup instead of bending a rule.
package io.github.stevdrey.dokene.messaging.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * {@code outageThreshold} outage signals in a row inside {@code outageWindow} trip the state to UNAVAILABLE.
 * {@code recoverySuccesses} successes in a row give HEALTHY. A HEALTHY or DEGRADED row with no signal for
 * {@code staleAfter} reads as UNKNOWN. The cool down starts at {@code coolDownInitial} and doubles up to
 * {@code coolDownMax}. {@code probeLease} is how long one probe may hold the lease.
 */
public record ProviderHealthSettings(Mode mode, int outageThreshold, Duration outageWindow, int recoverySuccesses,
        Duration staleAfter, Duration coolDownInitial, Duration coolDownMax, Duration probeLease) {

    /** ENFORCE refuses and probes; OBSERVE only tracks the state and never touches a send. */
    public enum Mode {
        ENFORCE, OBSERVE
    }

    public ProviderHealthSettings {
        Objects.requireNonNull(mode, "Provider health mode is required");
        requireBetween("outage-threshold", outageThreshold, 1, 20);
        requireBetween("recovery-successes", recoverySuccesses, 1, 10);
        requireBetween("outage-window", outageWindow, Duration.ofSeconds(10), Duration.ofHours(1));
        requireBetween("stale-after", staleAfter, Duration.ofMinutes(1), Duration.ofHours(24));
        if (staleAfter.compareTo(outageWindow) <= 0) {
            throw new IllegalArgumentException("Provider health stale-after must be longer than outage-window");
        }
        requireBetween("cool-down-max", coolDownMax, Duration.ofSeconds(1), Duration.ofHours(1));
        requireBetween("cool-down-initial", coolDownInitial, Duration.ofSeconds(1), coolDownMax);
        requireBetween("probe-lease", probeLease, Duration.ofMillis(1), Duration.ofMinutes(1));
    }

    private static void requireBetween(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "Provider health " + name + " must be between " + min + " and " + max);
        }
    }

    private static void requireBetween(String name, Duration value, Duration min, Duration max) {
        Objects.requireNonNull(value, "Provider health " + name + " is required");
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(
                    "Provider health " + name + " must be between " + min + " and " + max);
        }
    }
}
