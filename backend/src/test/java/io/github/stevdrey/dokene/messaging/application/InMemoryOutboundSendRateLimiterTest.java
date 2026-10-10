// ABOUTME: Unit tests for the fixed minute window per tenant and its Retry-After (spec 0003 AC-2, AC-9).
// ABOUTME: A mutable clock walks the window boundary.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InMemoryOutboundSendRateLimiterTest {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-10T12:00:20Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final InMemoryOutboundSendRateLimiter limiter = new InMemoryOutboundSendRateLimiter(
            new OutboundMessagingProperties(null, 0, null, new OutboundMessagingProperties.SendRateLimit(3)), clock);
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @Test
    void grantsThePermitsOfTheWindowThenRefusesWithTheSecondsLeftRoundedUp() {
        assertThat(limiter.acquire(tenantA)).isEmpty();
        assertThat(limiter.acquire(tenantA)).isEmpty();
        assertThat(limiter.acquire(tenantA)).isEmpty();
        // 20 seconds into the minute: 40 left.
        assertThat(limiter.acquire(tenantA)).contains(Duration.ofSeconds(40));
        now.set(Instant.parse("2026-10-10T12:00:59.500Z"));
        assertThat(limiter.acquire(tenantA)).contains(Duration.ofSeconds(1));
    }

    @Test
    void keysOnTheTenantOnlyAndResetsAtTheNextMinute() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire(tenantA);
        }
        assertThat(limiter.acquire(tenantA)).isPresent();
        assertThat(limiter.acquire(tenantB)).isEmpty();
        now.set(Instant.parse("2026-10-10T12:01:00Z"));
        assertThat(limiter.acquire(tenantA)).isEmpty();
    }

    @Test
    void propertiesFallBackToTheDocumentedDefaults() {
        var defaults = new OutboundMessagingProperties(null, 0, null, null);
        assertThat(defaults.outbound().enabled()).isFalse();
        assertThat(defaults.maxSendAttempts()).isEqualTo(3);
        assertThat(defaults.sendTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.sendRateLimit().perTenantPerMinute()).isEqualTo(30);
    }
}
