// ABOUTME: The pure rules of the admission budget (ADR 0040): the effective tenant minute limit and its reason, when a
// ABOUTME: window is full and how long until it frees a unit, and the pause. Counts, times and settings in, a verdict out.
package io.github.stevdrey.dokene.admission;

import io.github.stevdrey.dokene.admission.AdmissionDecision.Denied;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

final class AdmissionPolicy {
    static final Duration MINUTE = Duration.ofSeconds(60);
    static final Duration DAY = Duration.ofHours(24);
    /** No time can be promised for a switch, and no retry delay is ever shorter than this. */
    static final Duration SHORTEST_RETRY = Duration.ofSeconds(1);

    private final AdmissionProperties settings;

    AdmissionPolicy(AdmissionProperties settings) {
        this.settings = Objects.requireNonNull(settings, "Settings are required");
    }

    /** The tenant's share of the global minute limit, rounded down and never below one. */
    int tenantShare() {
        long share = (long) settings.send().globalPerMinute() * settings.send().tenantSharePercent() / 100;
        return (int) Math.max(1, share);
    }

    int tenantMinuteLimit() {
        return Math.min(settings.send().tenantPerMinute(), tenantShare());
    }

    /** Which of the two minute limits is the one in force; the tenant's own limit wins a tie. */
    AdmissionDenialReason tenantMinuteReason() {
        return settings.send().tenantPerMinute() <= tenantShare()
                ? AdmissionDenialReason.TENANT_MINUTE
                : AdmissionDenialReason.TENANT_SHARE;
    }

    /** The start of a window that ends at {@code now}; a row counts only when it is strictly after this. */
    Instant windowStart(Instant now, Duration window) {
        return now.minus(window);
    }

    /** Empty while the window has room for one more. */
    Optional<Denied> full(WindowUsage usage, int limit, Duration window, Instant now, AdmissionDenialReason reason) {
        if (usage.count() < limit) {
            return Optional.empty();
        }
        Instant freesAt = Objects.requireNonNull(usage.freesAt(), "A full window names the row that frees it");
        return Optional.of(new Denied(reason, atLeastShortest(Duration.between(now, freesAt.plus(window)))));
    }

    /** Empty unless the pause ends after {@code now}. */
    Optional<Denied> paused(Optional<Instant> pausedUntil, Instant now) {
        return pausedUntil.filter(until -> until.isAfter(now))
                .map(until -> new Denied(AdmissionDenialReason.TENANT_PAUSED,
                        atLeastShortest(Duration.between(now, until))));
    }

    Denied switchOff() {
        return new Denied(AdmissionDenialReason.SWITCH_OFF, SHORTEST_RETRY);
    }

    private static Duration atLeastShortest(Duration delay) {
        return delay.compareTo(SHORTEST_RETRY) < 0 ? SHORTEST_RETRY : delay;
    }
}
