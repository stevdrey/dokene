package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultFollowUpRecommendationRateLimiterTest {

    @Test
    void acquireSucceedsWithinConfiguredLimits() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T10:00:00Z"));
        DefaultFollowUpRecommendationRateLimiter limiter = new DefaultFollowUpRecommendationRateLimiter(
                2, 2, Duration.ofSeconds(5), clock);

        TenantId tenantId = TenantId.random();
        IdentityId actorId = new IdentityId(UUID.randomUUID());

        assertThatCode(() -> limiter.acquire(tenantId, actorId)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire(tenantId, actorId)).doesNotThrowAnyException();
    }

    @Test
    void acquireRejectsWhenActorQuotaExceeded() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T10:00:00Z"));
        DefaultFollowUpRecommendationRateLimiter limiter = new DefaultFollowUpRecommendationRateLimiter(
                10, 2, Duration.ofSeconds(5), clock);

        TenantId tenantId = TenantId.random();
        IdentityId actorId = new IdentityId(UUID.randomUUID());

        limiter.acquire(tenantId, actorId);
        limiter.acquire(tenantId, actorId);

        assertThatThrownBy(() -> limiter.acquire(tenantId, actorId))
                .isInstanceOf(RecommendationRateLimitExceededException.class)
                .hasMessageContaining("Actor recommendation rate limit exceeded");
    }

    @Test
    void acquireRejectsWhenTenantQuotaExceededAcrossActors() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T10:00:00Z"));
        DefaultFollowUpRecommendationRateLimiter limiter = new DefaultFollowUpRecommendationRateLimiter(
                2, 2, Duration.ofSeconds(5), clock);

        TenantId tenantId = TenantId.random();
        IdentityId actor1 = new IdentityId(UUID.randomUUID());
        IdentityId actor2 = new IdentityId(UUID.randomUUID());
        IdentityId actor3 = new IdentityId(UUID.randomUUID());

        limiter.acquire(tenantId, actor1);
        limiter.acquire(tenantId, actor2);

        assertThatThrownBy(() -> limiter.acquire(tenantId, actor3))
                .isInstanceOf(RecommendationRateLimitExceededException.class)
                .hasMessageContaining("Tenant recommendation rate limit exceeded");
    }

    @Test
    void permitsReplenishAfterSlidingWindowExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T10:00:00Z"));
        DefaultFollowUpRecommendationRateLimiter limiter = new DefaultFollowUpRecommendationRateLimiter(
                1, 1, Duration.ofSeconds(5), clock);

        TenantId tenantId = TenantId.random();
        IdentityId actorId = new IdentityId(UUID.randomUUID());

        limiter.acquire(tenantId, actorId);

        assertThatThrownBy(() -> limiter.acquire(tenantId, actorId))
                .isInstanceOf(RecommendationRateLimitExceededException.class);

        // Advance past the 1-minute sliding window
        clock.advance(Duration.ofSeconds(61));

        assertThatCode(() -> limiter.acquire(tenantId, actorId)).doesNotThrowAnyException();
    }

    @Test
    void acquireRejectionDerivesDynamicRetryAfterFromOldestRetainedTimestamp() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T10:00:00Z"));
        DefaultFollowUpRecommendationRateLimiter limiter = new DefaultFollowUpRecommendationRateLimiter(
                1, 1, Duration.ofSeconds(5), clock);

        TenantId tenantId = TenantId.random();
        IdentityId actorId = new IdentityId(UUID.randomUUID());

        limiter.acquire(tenantId, actorId);

        // Advance 20 seconds; window remaining until T+60s is 40 seconds
        clock.advance(Duration.ofSeconds(20));

        assertThatThrownBy(() -> limiter.acquire(tenantId, actorId))
                .isInstanceOf(RecommendationRateLimitExceededException.class)
                .satisfies(ex -> {
                    RecommendationRateLimitExceededException rateLimitEx = (RecommendationRateLimitExceededException) ex;
                    assertThat(rateLimitEx.retryAfter()).isEqualTo(Duration.ofSeconds(40));
                    assertThat(rateLimitEx.retryAfterSeconds()).isEqualTo(40);
                });
    }

    @Test
    void evictExpiredBucketsRemovesInactiveTenantAndActorEntries() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T10:00:00Z"));
        DefaultFollowUpRecommendationRateLimiter limiter = new DefaultFollowUpRecommendationRateLimiter(
                2, 2, Duration.ofSeconds(5), clock);

        TenantId tenant1 = TenantId.random();
        IdentityId actor1 = new IdentityId(UUID.randomUUID());

        limiter.acquire(tenant1, actor1);
        assertThat(limiter.hasTenantBucket(tenant1)).isTrue();
        assertThat(limiter.hasActorBucket(tenant1, actor1)).isTrue();

        // Advance clock past the 1-minute window
        clock.advance(Duration.ofSeconds(61));

        limiter.evictExpiredBuckets();

        assertThat(limiter.hasTenantBucket(tenant1)).isFalse();
        assertThat(limiter.hasActorBucket(tenant1, actor1)).isFalse();
    }

    private static class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone = ZoneId.of("UTC");

        MutableClock(Instant start) {
            this.current = start;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
