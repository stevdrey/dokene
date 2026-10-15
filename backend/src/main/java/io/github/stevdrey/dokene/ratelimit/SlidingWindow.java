package io.github.stevdrey.dokene.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;

/**
 * Timestamps of recent events inside a sliding window of fixed length. An event leaves the window once it is exactly
 * {@code length} old. Not thread-safe: callers guard each instance (a monitor or {@code ConcurrentHashMap.compute}).
 */
public final class SlidingWindow {
    private static final Duration MINIMUM_WAIT = Duration.ofSeconds(1);

    private final Duration length;
    private final Deque<Instant> events = new ArrayDeque<>();

    public SlidingWindow(Duration length) {
        this.length = Objects.requireNonNull(length, "Window length is required");
        if (length.isZero() || length.isNegative()) {
            throw new IllegalArgumentException("Window length must be positive");
        }
    }

    /** Events still inside the window at {@code now}; expired ones are dropped. */
    public int count(Instant now) {
        Instant windowStart = now.minus(length);
        while (!events.isEmpty() && !events.peekFirst().isAfter(windowStart)) {
            events.pollFirst();
        }
        return events.size();
    }

    /** Records an event at {@code now}; events are expected in non-decreasing time order. */
    public void record(Instant now) {
        events.addLast(Objects.requireNonNull(now, "Event time is required"));
    }

    /** Undoes the most recent event recorded at {@code at}; returns whether one was found. */
    public boolean removeLatest(Instant at) {
        return events.removeLastOccurrence(at);
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    /** How long until fewer than {@code limit} events remain: zero when already below, otherwise at least a second. */
    public Duration untilBelow(int limit, Instant now) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be positive");
        }
        int over = count(now) - limit;
        if (over < 0) {
            return Duration.ZERO;
        }
        Iterator<Instant> oldestFirst = events.iterator();
        Instant releasing = oldestFirst.next();
        for (int skip = over; skip > 0; skip--) {
            releasing = oldestFirst.next();
        }
        Duration wait = Duration.between(now, releasing.plus(length));
        return wait.compareTo(MINIMUM_WAIT) < 0 ? MINIMUM_WAIT : wait;
    }
}
