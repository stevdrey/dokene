// ABOUTME: A UTC test clock that only moves when a test moves it, shared by the denial audit bound tests (spec 0020)
// ABOUTME: so a window can be ended without waiting for it.
package io.github.stevdrey.dokene.audit.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        now = new AtomicReference<>(start);
    }

    public void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
