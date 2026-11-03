// ABOUTME: Throttles the webhook route's WARN lines (issue 306): the first line per message, then one per minute with
// ABOUTME: the count it held back, so an anonymous flood cannot become a log flood. Counters still see every refusal.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * Lines are keyed by their text, which is always a fixed message or a closed reason and never request data, so the map
 * stays as small as that vocabulary. A held-back count is reported with the next line of the same text once the
 * interval has passed; refusals are cheap and short, so one monitor guards the map.
 */
public final class WebhookRejectionLog {
    static final Duration INTERVAL = Duration.ofMinutes(1);

    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    public WebhookRejectionLog(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /** {@code Webhook rejected reason=<R>} through the refusing class's logger. */
    public void rejected(Logger log, Reason reason) {
        warn(log, "Webhook rejected reason=" + reason.name());
    }

    /** {@code line} must be a constant or built only from a closed vocabulary; it is the throttling key. */
    public void warn(Logger log, String line) {
        long heldBack = admit(line, clock.instant());
        if (heldBack == 0) {
            log.warn(line);
        } else if (heldBack > 0) {
            log.warn("{} suppressed={}", line, heldBack);
        }
    }

    /** The count held back since the last line when this one is written, or {@code -1} when it is held back too. */
    private synchronized long admit(String line, Instant now) {
        Window window = windows.get(line);
        if (window == null || !now.isBefore(window.opened.plus(INTERVAL))) {
            windows.put(line, new Window(now));
            return window == null ? 0 : window.heldBack;
        }
        window.heldBack++;
        return -1;
    }

    private static final class Window {
        private final Instant opened;
        private long heldBack;

        private Window(Instant opened) {
            this.opened = opened;
        }
    }
}
