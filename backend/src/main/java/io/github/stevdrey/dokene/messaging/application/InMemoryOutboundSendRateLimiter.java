// ABOUTME: Fixed one minute window per tenant held in process memory; Phase 4 replaces it for multi instance sending.
// ABOUTME: Retry-After is the whole seconds left in the window, rounded up, never below one.
package io.github.stevdrey.dokene.messaging.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryOutboundSendRateLimiter implements OutboundSendRateLimiter {
    private static final long WINDOW_MILLIS = 60_000L;

    private final OutboundMessagingProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();

    public InMemoryOutboundSendRateLimiter(OutboundMessagingProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "Messaging properties are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    public Optional<Duration> acquire(UUID tenantId) {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        long now = clock.millis();
        long windowStart = now - Math.floorMod(now, WINDOW_MILLIS);
        int limit = properties.sendRateLimit().perTenantPerMinute();
        Window window = windows.compute(tenantId, (id, current) ->
                current == null || current.start() != windowStart ? new Window(windowStart, 1)
                        : new Window(windowStart, current.permits() + 1));
        if (window.permits() <= limit) {
            return Optional.empty();
        }
        long millisLeft = windowStart + WINDOW_MILLIS - now;
        long seconds = Math.max(1, (millisLeft + 999) / 1000);
        return Optional.of(Duration.ofSeconds(seconds));
    }

    private record Window(long start, int permits) {
    }
}
