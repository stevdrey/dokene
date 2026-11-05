package io.github.stevdrey.dokene.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe in-memory budget of outbound calls per key within a sliding window. Every granted call counts, whatever
 * its outcome, so a caller cannot make more than {@code limit} calls per key per window. Idle keys are evicted, and state
 * is per instance and lost on restart. One monitor guards the map: callers are rare administrative requests.
 */
public final class CallBudget<K> {
    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<K, SlidingWindow> calls = new HashMap<>();
    private Instant lastEviction = Instant.EPOCH;

    public CallBudget(int limit, Duration window, Clock clock) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Call limit must be positive");
        }
        this.window = Objects.requireNonNull(window, "Window is required");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Window must be positive");
        }
        this.limit = limit;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /**
     * Grants and records one call for {@code key}: empty when granted, otherwise how long until a call frees up (at
     * least a second). A refused call records nothing.
     */
    public synchronized Optional<Duration> acquire(K key) {
        Objects.requireNonNull(key, "Key is required");
        Instant now = clock.instant();
        maybeEvictExpired(now);
        SlidingWindow recent = calls.computeIfAbsent(key, ignored -> new SlidingWindow(window));
        if (recent.count(now) >= limit) {
            return Optional.of(recent.untilBelow(limit, now));
        }
        recent.record(now);
        return Optional.empty();
    }

    synchronized boolean tracks(K key) {
        return calls.containsKey(key);
    }

    private void maybeEvictExpired(Instant now) {
        if (now.isAfter(lastEviction.plus(window))) {
            lastEviction = now;
            calls.values().removeIf(recent -> recent.count(now) == 0);
        }
    }
}
