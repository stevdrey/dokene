package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Thread-safe in-memory sliding window over rejected phone-number-id mappings, per tenant and per actor within the
 * tenant. Only rejections are counted, so successful writes never consume budget. Keys are tenant and identity ids
 * only: the candidate phone number id never reaches this class. State is per instance and lost on restart.
 */
@Component
@EnableConfigurationProperties(MappingRejectionLimitProperties.class)
public class MappingRejectionLimiter {
    private final int tenantRejections;
    private final int actorRejections;
    private final Duration window;
    private final Clock clock;

    private final ConcurrentHashMap<TenantId, Deque<Instant>> tenantBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ActorKey, Deque<Instant>> actorBuckets = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastEviction = new AtomicReference<>(Instant.EPOCH);

    public MappingRejectionLimiter(MappingRejectionLimitProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "Properties are required");
        this.tenantRejections = properties.tenantRejections();
        this.actorRejections = properties.actorRejections();
        this.window = properties.window();
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    private record ActorKey(TenantId tenantId, IdentityId identityId) {
    }

    /** Throws {@link IntegrationRateLimitedException} while the tenant or the actor has exhausted its budget. */
    public void requireAvailable(TenantId tenantId, IdentityId identityId) {
        Instant now = clock.instant();
        maybeEvictExpired(now);
        Duration delay = longest(blockedFor(tenantBuckets, Objects.requireNonNull(tenantId, "Tenant ID is required"),
                tenantRejections, now), blockedFor(actorBuckets, actorKey(tenantId, identityId), actorRejections, now));
        if (delay != null) {
            throw new IntegrationRateLimitedException(delay);
        }
    }

    /** Records one rejected mapping; returns true when this rejection exhausted the tenant or the actor budget. */
    public boolean recordRejection(TenantId tenantId, IdentityId identityId) {
        Instant now = clock.instant();
        maybeEvictExpired(now);
        boolean tenantExhausted = record(tenantBuckets, Objects.requireNonNull(tenantId, "Tenant ID is required"),
                tenantRejections, now);
        boolean actorExhausted = record(actorBuckets, actorKey(tenantId, identityId), actorRejections, now);
        return tenantExhausted || actorExhausted;
    }

    void evictExpired(Instant now) {
        Instant windowStart = now.minus(window);
        evict(tenantBuckets, windowStart);
        evict(actorBuckets, windowStart);
    }

    boolean hasTenantBucket(TenantId tenantId) {
        return tenantBuckets.containsKey(tenantId);
    }

    boolean hasActorBucket(TenantId tenantId, IdentityId identityId) {
        return actorBuckets.containsKey(actorKey(tenantId, identityId));
    }

    private static ActorKey actorKey(TenantId tenantId, IdentityId identityId) {
        return new ActorKey(tenantId, Objects.requireNonNull(identityId, "Identity ID is required"));
    }

    /** Null when available; otherwise how long until enough rejections leave the window to fall below the limit. */
    private <K> Duration blockedFor(ConcurrentHashMap<K, Deque<Instant>> buckets, K key, int limit, Instant now) {
        Instant[] releasedAt = new Instant[1];
        buckets.computeIfPresent(key, (k, bucket) -> {
            prune(bucket, now.minus(window));
            if (bucket.size() >= limit) {
                Iterator<Instant> oldestFirst = bucket.iterator();
                Instant releasing = oldestFirst.next();
                for (int skip = bucket.size() - limit; skip > 0; skip--) {
                    releasing = oldestFirst.next();
                }
                releasedAt[0] = releasing.plus(window);
            }
            return bucket.isEmpty() ? null : bucket;
        });
        if (releasedAt[0] == null) {
            return null;
        }
        Duration delay = Duration.between(now, releasedAt[0]);
        return delay.isNegative() || delay.isZero() ? Duration.ofSeconds(1) : delay;
    }

    private <K> boolean record(ConcurrentHashMap<K, Deque<Instant>> buckets, K key, int limit, Instant now) {
        boolean[] exhausted = new boolean[1];
        buckets.compute(key, (k, bucket) -> {
            Deque<Instant> current = bucket == null ? new ArrayDeque<>() : bucket;
            prune(current, now.minus(window));
            current.addLast(now);
            exhausted[0] = current.size() == limit;
            return current;
        });
        return exhausted[0];
    }

    private void maybeEvictExpired(Instant now) {
        Instant last = lastEviction.get();
        if (now.isAfter(last.plus(window)) && lastEviction.compareAndSet(last, now)) {
            evictExpired(now);
        }
    }

    private static <K> void evict(ConcurrentHashMap<K, Deque<Instant>> buckets, Instant windowStart) {
        buckets.forEach((key, ignored) -> buckets.computeIfPresent(key, (k, bucket) -> {
            prune(bucket, windowStart);
            return bucket.isEmpty() ? null : bucket;
        }));
    }

    private static void prune(Deque<Instant> bucket, Instant windowStart) {
        while (!bucket.isEmpty() && !bucket.peekFirst().isAfter(windowStart)) {
            bucket.pollFirst();
        }
    }

    private static Duration longest(Duration first, Duration second) {
        if (first == null) {
            return second;
        }
        return second == null || first.compareTo(second) >= 0 ? first : second;
    }
}
