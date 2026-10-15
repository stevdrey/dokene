package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ratelimit.SlidingWindow;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Thread-safe in-memory sliding window rate limiter protecting AI recommendation provider calls.
 * Enforces per-tenant and per-actor limits.
 */
@Component
@EnableConfigurationProperties(RecommendationRateLimitProperties.class)
public class DefaultFollowUpRecommendationRateLimiter implements FollowUpRecommendationRateLimiter {
    private static final Duration WINDOW_DURATION = Duration.ofMinutes(1);
    private static final Duration EVICTION_INTERVAL = Duration.ofMinutes(1);

    private final int tenantPermitsPerMinute;
    private final int actorPermitsPerMinute;
    private final Duration retryAfter;
    private final Clock clock;

    private final ConcurrentHashMap<TenantId, SlidingWindow> tenantRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SlidingWindow> actorRequests = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastEviction = new AtomicReference<>(Instant.EPOCH);

    @Autowired
    public DefaultFollowUpRecommendationRateLimiter(
            RecommendationRateLimitProperties properties,
            Clock clock) {
        RecommendationRateLimitProperties props = properties != null ? properties : RecommendationRateLimitProperties.defaults();
        this.tenantPermitsPerMinute = props.tenantPermitsPerMinute();
        this.actorPermitsPerMinute = props.actorPermitsPerMinute();
        this.retryAfter = props.retryAfter();
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    public DefaultFollowUpRecommendationRateLimiter(
            int tenantPermitsPerMinute,
            int actorPermitsPerMinute,
            Duration retryAfter,
            Clock clock) {
        this(new RecommendationRateLimitProperties(tenantPermitsPerMinute, actorPermitsPerMinute, retryAfter), clock);
    }

    public DefaultFollowUpRecommendationRateLimiter(Clock clock) {
        this(RecommendationRateLimitProperties.defaults(), clock);
    }

    public DefaultFollowUpRecommendationRateLimiter() {
        this(Clock.systemUTC());
    }

    @Override
    public void acquire(TenantId tenantId, IdentityId identityId) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(identityId, "Identity ID is required");

        Instant now = clock.instant();
        maybeEvictExpired(now);

        checkAndRecord(tenantRequests, tenantId, tenantPermitsPerMinute, now,
                "Tenant recommendation rate limit exceeded");

        String actorKey = tenantId.value() + ":" + identityId.value();
        try {
            checkAndRecord(actorRequests, actorKey, actorPermitsPerMinute, now,
                    "Actor recommendation rate limit exceeded");
        } catch (RecommendationRateLimitExceededException ex) {
            rollback(tenantRequests, tenantId, now);
            throw ex;
        }
    }

    public void evictExpiredBuckets() {
        evictExpired(clock.instant());
    }

    private void maybeEvictExpired(Instant now) {
        Instant last = lastEviction.get();
        if (now.isAfter(last.plus(EVICTION_INTERVAL))) {
            if (lastEviction.compareAndSet(last, now)) {
                evictExpired(now);
            }
        }
    }

    void evictExpired(Instant now) {
        evictFromMap(tenantRequests, now);
        evictFromMap(actorRequests, now);
    }

    private <K> void evictFromMap(ConcurrentHashMap<K, SlidingWindow> map, Instant now) {
        map.forEach((key, ignored) -> map.computeIfPresent(key,
                (k, window) -> window.count(now) == 0 ? null : window));
    }

    boolean hasTenantBucket(TenantId tenantId) {
        return tenantRequests.containsKey(tenantId);
    }

    boolean hasActorBucket(TenantId tenantId, IdentityId identityId) {
        return actorRequests.containsKey(tenantId.value() + ":" + identityId.value());
    }

    private <K> void checkAndRecord(
            ConcurrentHashMap<K, SlidingWindow> map,
            K key,
            int maxPermits,
            Instant now,
            String errorMessage) {
        map.compute(key, (k, window) -> {
            SlidingWindow current = window == null ? new SlidingWindow(WINDOW_DURATION) : window;
            if (current.count(now) >= maxPermits) {
                throw new RecommendationRateLimitExceededException(errorMessage,
                        current.untilBelow(maxPermits, now));
            }
            current.record(now);
            return current;
        });
    }

    private <K> void rollback(ConcurrentHashMap<K, SlidingWindow> map, K key, Instant recordedAt) {
        map.computeIfPresent(key, (k, window) -> {
            window.removeLatest(recordedAt);
            return window.isEmpty() ? null : window;
        });
    }
}
