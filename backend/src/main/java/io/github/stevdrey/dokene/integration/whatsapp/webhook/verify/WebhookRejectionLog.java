// ABOUTME: Throttles the webhook route's WARN lines (issue 306): the first line per logger and message, then one per
// ABOUTME: minute with the count it held back, so an anonymous flood cannot become a log flood. Counters see everything.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Lines are keyed by the refusing class's logger and a closed message (a {@link Reason} or a {@link Line}), never by
 * request data, so the map stays as small as that vocabulary. A held-back count is reported with the next line of the
 * same key once the interval has passed, or by {@link #flush} when the flood has stopped.
 */
public final class WebhookRejectionLog {
    static final Duration INTERVAL = Duration.ofMinutes(1);

    /** The route's fixed lines that are not a verification reason. */
    public enum Line {
        UNREADABLE("Webhook body could not be read"),
        OVERLOADED("Webhook rejected: every admission permit is taken");

        private final String text;

        Line(String text) {
            this.text = text;
        }
    }

    private final Clock clock;
    private final Map<Key, Window> windows = new ConcurrentHashMap<>();

    public WebhookRejectionLog(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /** {@code Webhook rejected reason=<R>} through the refusing class's logger. */
    public void rejected(Logger log, Reason reason) {
        write(log, "Webhook rejected reason=" + reason.name());
    }

    public void warn(Logger log, Line line) {
        write(log, line.text);
    }

    /** Reports, once, every count held back in a window that has ended; scheduled by the configuration. */
    public void flush() {
        Instant now = clock.instant();
        // Per key under compute, so a concurrent write can neither lose nor repeat a held-back count.
        for (Key key : windows.keySet()) {
            windows.computeIfPresent(key, (ignored, window) -> {
                long heldBack = window.heldBack.get();
                if (heldBack > 0 && !now.isBefore(window.opened.plus(INTERVAL))) {
                    window.log.warn("{} suppressed={}", key.line, heldBack);
                    window.heldBack.set(0);
                }
                return window;
            });
        }
    }

    private void write(Logger log, String line) {
        Instant now = clock.instant();
        long[] reported = {-1};
        windows.compute(new Key(log.getName(), line), (key, window) -> {
            if (window == null || !now.isBefore(window.opened.plus(INTERVAL))) {
                reported[0] = window == null ? 0 : window.heldBack.get();
                return new Window(log, now);
            }
            window.heldBack.incrementAndGet();
            return window;
        });
        if (reported[0] == 0) {
            log.warn(line);
        } else if (reported[0] > 0) {
            log.warn("{} suppressed={}", line, reported[0]);
        }
    }

    private record Key(String logger, String line) {
    }

    private static final class Window {
        private final Logger log;
        private final Instant opened;
        private final AtomicLong heldBack = new AtomicLong();

        private Window(Logger log, Instant opened) {
            this.log = log;
            this.opened = opened;
        }
    }
}
