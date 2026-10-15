package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding window over rejected phone-number-id mappings, per tenant and per actor within the tenant. Only
 * rejections are counted, so successful writes never consume budget. Every mapping change first reserves one unit of
 * both budgets until its outcome is known, so concurrent attempts can never reveal more conflicts than the budget
 * allows. Keys are tenant and identity ids only: the candidate phone number id never reaches this class. State is per
 * instance and lost on restart. Admin writes are rare, so one monitor guards both budgets and keeps them atomic.
 */
@Component
@EnableConfigurationProperties(MappingRejectionLimitProperties.class)
public class MappingRejectionLimiter {
    private final int tenantRejections;
    private final int actorRejections;
    private final Duration window;
    private final Clock clock;

    private final Map<TenantId, Budget> tenantBudgets = new HashMap<>();
    private final Map<ActorKey, Budget> actorBudgets = new HashMap<>();
    private Instant lastEviction = Instant.EPOCH;

    public MappingRejectionLimiter(MappingRejectionLimitProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "Properties are required");
        this.tenantRejections = properties.tenantRejections();
        this.actorRejections = properties.actorRejections();
        this.window = properties.window();
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    private record ActorKey(TenantId tenantId, IdentityId identityId) {
    }

    /** Rejections still inside the window plus attempts whose outcome is not known yet. */
    private static final class Budget {
        private final Deque<Instant> rejections = new ArrayDeque<>();
        private int inFlight;

        boolean idle() {
            return rejections.isEmpty() && inFlight == 0;
        }
    }

    /**
     * Reserves one unit of the tenant and actor budgets for a mapping change, or throws
     * {@link IntegrationRateLimitedException} when either has no unit left. Close the attempt once the write finished;
     * call {@link MappingAttempt#reject()} first when the number was already mapped by another tenant.
     */
    public synchronized MappingAttempt begin(TenantId tenantId, IdentityId identityId) {
        Instant now = clock.instant();
        maybeEvictExpired(now);
        Budget tenant = tenantBudgets.computeIfAbsent(Objects.requireNonNull(tenantId, "Tenant ID is required"),
                key -> new Budget());
        Budget actor = actorBudgets.computeIfAbsent(actorKey(tenantId, identityId), key -> new Budget());
        Duration delay = longest(blockedFor(tenant, tenantRejections, now), blockedFor(actor, actorRejections, now));
        if (delay != null) {
            evictIdle();
            throw new IntegrationRateLimitedException(delay);
        }
        tenant.inFlight++;
        actor.inFlight++;
        return new MappingAttempt(tenant, actor);
    }

    /** A reservation held by one mapping change until it is rejected or closed. */
    public final class MappingAttempt implements AutoCloseable {
        private final Budget tenant;
        private final Budget actor;
        private boolean settled;

        private MappingAttempt(Budget tenant, Budget actor) {
            this.tenant = tenant;
            this.actor = actor;
        }

        /** Turns the reservation into a rejection; returns true when it exhausted the tenant or actor budget. */
        public boolean reject() {
            synchronized (MappingRejectionLimiter.this) {
                if (settled) {
                    return false;
                }
                settle();
                Instant now = clock.instant();
                boolean tenantExhausted = record(tenant, tenantRejections, now);
                boolean actorExhausted = record(actor, actorRejections, now);
                return tenantExhausted || actorExhausted;
            }
        }

        /** Releases the reservation of an attempt that was not rejected; does nothing after {@link #reject()}. */
        @Override
        public void close() {
            synchronized (MappingRejectionLimiter.this) {
                if (!settled) {
                    settle();
                    evictIdle();
                }
            }
        }

        private void settle() {
            settled = true;
            tenant.inFlight--;
            actor.inFlight--;
        }
    }

    synchronized void evictExpired(Instant now) {
        Instant windowStart = now.minus(window);
        tenantBudgets.values().forEach(budget -> prune(budget, windowStart));
        actorBudgets.values().forEach(budget -> prune(budget, windowStart));
        evictIdle();
    }

    synchronized boolean hasTenantBucket(TenantId tenantId) {
        return tenantBudgets.containsKey(tenantId);
    }

    synchronized boolean hasActorBucket(TenantId tenantId, IdentityId identityId) {
        return actorBudgets.containsKey(actorKey(tenantId, identityId));
    }

    private static ActorKey actorKey(TenantId tenantId, IdentityId identityId) {
        return new ActorKey(tenantId, Objects.requireNonNull(identityId, "Identity ID is required"));
    }

    /**
     * Null while a unit is left; otherwise how long until one frees up. An in-flight attempt that succeeds frees its
     * unit as soon as it finishes, so while any is pending the wait is one second; otherwise it lasts until enough
     * rejections leave the window.
     */
    private Duration blockedFor(Budget budget, int limit, Instant now) {
        prune(budget, now.minus(window));
        int overLimit = budget.rejections.size() + budget.inFlight - limit;
        if (overLimit < 0) {
            return null;
        }
        if (budget.inFlight > 0) {
            return Duration.ofSeconds(1);
        }
        Iterator<Instant> oldestFirst = budget.rejections.iterator();
        Instant releasing = oldestFirst.next();
        for (int skip = overLimit; skip > 0; skip--) {
            releasing = oldestFirst.next();
        }
        Duration delay = Duration.between(now, releasing.plus(window));
        return delay.isNegative() || delay.isZero() ? Duration.ofSeconds(1) : delay;
    }

    private boolean record(Budget budget, int limit, Instant now) {
        prune(budget, now.minus(window));
        budget.rejections.addLast(now);
        return budget.rejections.size() == limit;
    }

    private void maybeEvictExpired(Instant now) {
        if (now.isAfter(lastEviction.plus(window))) {
            lastEviction = now;
            evictExpired(now);
        }
    }

    private void evictIdle() {
        tenantBudgets.values().removeIf(Budget::idle);
        actorBudgets.values().removeIf(Budget::idle);
    }

    private static void prune(Budget budget, Instant windowStart) {
        while (!budget.rejections.isEmpty() && !budget.rejections.peekFirst().isAfter(windowStart)) {
            budget.rejections.pollFirst();
        }
    }

    private static Duration longest(Duration first, Duration second) {
        if (first == null) {
            return second;
        }
        return second == null || first.compareTo(second) >= 0 ? first : second;
    }
}
