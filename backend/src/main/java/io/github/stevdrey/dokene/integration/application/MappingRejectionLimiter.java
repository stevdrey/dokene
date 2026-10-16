package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.ratelimit.SlidingWindow;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding window over rejected phone-number ownership proofs, per tenant and per actor within the tenant.
 * Only rejections are counted, so successful proofs never consume budget. Every ownership verification first reserves
 * one unit of both budgets until its outcome is known, so concurrent attempts can never reveal more rejections than the
 * budget allows. Keys are tenant and identity ids only: the candidate phone number id never reaches this class. State is per
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
        private final SlidingWindow rejections;
        private int inFlight;

        Budget(Duration window) {
            this.rejections = new SlidingWindow(window);
        }

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
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        ActorKey actorKey = actorKey(tenantId, identityId);
        Budget tenant = tenantBudgets.computeIfAbsent(tenantId, key -> new Budget(window));
        Budget actor = actorBudgets.computeIfAbsent(actorKey, key -> new Budget(window));
        Duration delay = longest(blockedFor(tenant, tenantRejections, now), blockedFor(actor, actorRejections, now));
        if (delay != null) {
            removeIfIdle(tenantId, tenant, actorKey, actor);
            throw new IntegrationRateLimitedException(delay);
        }
        tenant.inFlight++;
        actor.inFlight++;
        return new MappingAttempt(tenantId, tenant, actorKey, actor);
    }

    /** A reservation held by one mapping change until it is rejected or closed. */
    public final class MappingAttempt implements AutoCloseable {
        private final TenantId tenantId;
        private final Budget tenant;
        private final ActorKey actorKey;
        private final Budget actor;
        private boolean settled;

        private MappingAttempt(TenantId tenantId, Budget tenant, ActorKey actorKey, Budget actor) {
            this.tenantId = tenantId;
            this.tenant = tenant;
            this.actorKey = actorKey;
            this.actor = actor;
        }

        /** Turns the reservation into a rejection counted against both budgets; does nothing once settled. */
        public void reject() {
            synchronized (MappingRejectionLimiter.this) {
                if (!settled) {
                    settle();
                    Instant now = clock.instant();
                    tenant.rejections.record(now);
                    actor.rejections.record(now);
                }
            }
        }

        /** Releases the reservation of an attempt that was not rejected; does nothing after {@link #reject()}. */
        @Override
        public void close() {
            synchronized (MappingRejectionLimiter.this) {
                if (!settled) {
                    settle();
                    removeIfIdle(tenantId, tenant, actorKey, actor);
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
        tenantBudgets.values().removeIf(budget -> budget.rejections.count(now) == 0 && budget.inFlight == 0);
        actorBudgets.values().removeIf(budget -> budget.rejections.count(now) == 0 && budget.inFlight == 0);
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
    private static Duration blockedFor(Budget budget, int limit, Instant now) {
        if (budget.rejections.count(now) + budget.inFlight < limit) {
            return null;
        }
        return budget.inFlight > 0 ? Duration.ofSeconds(1) : budget.rejections.untilBelow(limit, now);
    }

    private void maybeEvictExpired(Instant now) {
        if (now.isAfter(lastEviction.plus(window))) {
            lastEviction = now;
            evictExpired(now);
        }
    }

    /** Drops just the two budgets an attempt touched, once nothing in them is pending or inside the window. */
    private void removeIfIdle(TenantId tenantId, Budget tenant, ActorKey actorKey, Budget actor) {
        if (tenant.idle()) {
            tenantBudgets.remove(tenantId, tenant);
        }
        if (actor.idle()) {
            actorBudgets.remove(actorKey, actor);
        }
    }

    private static Duration longest(Duration first, Duration second) {
        if (first == null) {
            return second;
        }
        return second == null || first.compareTo(second) >= 0 ? first : second;
    }
}
