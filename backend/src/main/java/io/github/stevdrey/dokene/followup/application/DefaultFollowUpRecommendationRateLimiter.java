package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
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

    private final ConcurrentHashMap<TenantId, Deque<Instant>> tenantRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Instant>> actorRequests = new ConcurrentHashMap<>();
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

        Instant windowStart = now.minus(WINDOW_DURATION);

        checkAndRecord(tenantRequests, tenantId, tenantPermitsPerMinute, now, windowStart,
                "Tenant recommendation rate limit exceeded");

        String actorKey = tenantId.value() + ":" + identityId.value();
        try {
            checkAndRecord(actorRequests, actorKey, actorPermitsPerMinute, now, windowStart,
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
        Instant windowStart = now.minus(WINDOW_DURATION);
        evictFromMap(tenantRequests, windowStart);
        evictFromMap(actorRequests, windowStart);
    }

    private <K> void evictFromMap(ConcurrentHashMap<K, Deque<Instant>> map, Instant windowStart) {
        map.forEach((key, deque) -> {
            map.computeIfPresent(key, (k, d) -> {
                while (!d.isEmpty() && d.peekFirst().isBefore(windowStart)) {
                    d.pollFirst();
                }
                return d.isEmpty() ? null : d;
            });
        });
    }

    boolean hasTenantBucket(TenantId tenantId) {
        return tenantRequests.containsKey(tenantId);
    }

    boolean hasActorBucket(TenantId tenantId, IdentityId identityId) {
        return actorRequests.containsKey(tenantId.value() + ":" + identityId.value());
    }

    private <K> void checkAndRecord(
            ConcurrentHashMap<K, Deque<Instant>> map,
            K key,
            int maxPermits,
            Instant now,
            Instant windowStart,
            String errorMessage) {
        map.compute(key, (k, deque) -> {
            if (deque == null) {
                deque = new ArrayDeque<>();
            }
            while (!deque.isEmpty() && deque.peekFirst().isBefore(windowStart)) {
                deque.pollFirst();
            }
            if (deque.size() >= maxPermits) {
                Instant oldest = deque.peekFirst();
                Duration delay = Duration.between(now, oldest.plus(WINDOW_DURATION));
                if (delay.isNegative() || delay.isZero()) {
                    delay = Duration.ofSeconds(1);
                }
                throw new RecommendationRateLimitExceededException(errorMessage, delay);
            }
            deque.addLast(now);
            return deque;
        });
    }

    private <K> void rollback(ConcurrentHashMap<K, Deque<Instant>> map, K key, Instant recordedAt) {
        map.computeIfPresent(key, (k, deque) -> {
            deque.removeLastOccurrence(recordedAt);
            return deque.isEmpty() ? null : deque;
        });
    }
}
