package io.github.stevdrey.dokene.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Thread-safe in-memory budget of outbound provider calls per tenant and per actor within the tenant, over one sliding
 * window. A call is admitted only while both levels have a unit left, so one actor can never spend more than its own
 * share of the tenant's budget. An admitted operation is settled with the number of provider requests it really made:
 * zero refunds the admission and more than one charges the rest, so the bound is the limit plus whatever one admitted
 * operation can add. Refusals record nothing, and at most one refusal per actor per window is flagged for reporting, so
 * callers can audit denials without a looping caller multiplying the rows. Keys are opaque; state is per instance.
 */
public final class ActorCallBudget<T, A> {
    private final CallLimits limits;
    private final Clock clock;
    private final Map<T, SlidingWindow> tenants = new HashMap<>();
    private final Map<ActorKey<T, A>, SlidingWindow> actors = new HashMap<>();
    private final Map<ActorKey<T, A>, Instant> reported = new HashMap<>();
    private Instant lastEviction = Instant.EPOCH;

    public ActorCallBudget(CallLimits limits, Clock clock) {
        this.limits = Objects.requireNonNull(limits, "Limits are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    private record ActorKey<T, A>(T tenant, A actor) {
    }

    /** Why an operation was refused: the wait until a unit frees up, and whether this refusal should be reported. */
    public record Refusal(Duration retryAfter, boolean report) {
    }

    /** An admitted operation, settled once with the provider requests it made; later settlements do nothing. */
    public interface Ticket {
        void settle(int requests);
    }

    /**
     * Admits one operation for {@code actor} of {@code tenant} and records one unit at both levels, or throws the
     * exception {@code refused} builds when either level has none left.
     */
    public synchronized Ticket admit(T tenant, A actor, Function<Refusal, ? extends RuntimeException> refused) {
        ActorKey<T, A> key = new ActorKey<>(Objects.requireNonNull(tenant, "Tenant key is required"),
                Objects.requireNonNull(actor, "Actor key is required"));
        Objects.requireNonNull(refused, "Refusal factory is required");
        Instant now = clock.instant();
        maybeEvictExpired(now);
        SlidingWindow tenantCalls = tenants.computeIfAbsent(tenant, ignored -> new SlidingWindow(limits.window()));
        SlidingWindow actorCalls = actors.computeIfAbsent(key, ignored -> new SlidingWindow(limits.window()));
        Duration delay = longest(blockedFor(tenantCalls, limits.tenantCalls(), now),
                blockedFor(actorCalls, limits.actorCalls(), now));
        if (delay != null) {
            throw refused.apply(new Refusal(delay, firstReport(key, now)));
        }
        tenantCalls.record(now);
        actorCalls.record(now);
        return new AdmittedTicket(key, now);
    }

    synchronized boolean tracks(T tenant, A actor) {
        ActorKey<T, A> key = new ActorKey<>(tenant, actor);
        return tenants.containsKey(tenant) || actors.containsKey(key) || reported.containsKey(key);
    }

    private final class AdmittedTicket implements Ticket {
        private final ActorKey<T, A> key;
        private final Instant admittedAt;
        private boolean settled;

        private AdmittedTicket(ActorKey<T, A> key, Instant admittedAt) {
            this.key = key;
            this.admittedAt = admittedAt;
        }

        @Override
        public void settle(int requests) {
            if (requests < 0) {
                throw new IllegalArgumentException("Requests cannot be negative");
            }
            synchronized (ActorCallBudget.this) {
                if (settled) {
                    return;
                }
                settled = true;
                if (requests == 0) {
                    refund(tenants.get(key.tenant()));
                    refund(actors.get(key));
                    return;
                }
                Instant now = clock.instant();
                SlidingWindow tenantCalls = tenants.computeIfAbsent(key.tenant(),
                        ignored -> new SlidingWindow(limits.window()));
                SlidingWindow actorCalls = actors.computeIfAbsent(key, ignored -> new SlidingWindow(limits.window()));
                for (int extra = 1; extra < requests; extra++) {
                    tenantCalls.record(now);
                    actorCalls.record(now);
                }
            }
        }

        private void refund(SlidingWindow calls) {
            if (calls != null) {
                calls.removeLatest(admittedAt);
            }
        }
    }

    /** True for the first refusal of this actor in a window; later ones within that window are not reported. */
    private boolean firstReport(ActorKey<T, A> key, Instant now) {
        Instant last = reported.get(key);
        if (last != null && now.isBefore(last.plus(limits.window()))) {
            return false;
        }
        reported.put(key, now);
        return true;
    }

    /** Null while a unit is left; otherwise how long until one frees up. */
    private static Duration blockedFor(SlidingWindow calls, int limit, Instant now) {
        return calls.count(now) < limit ? null : calls.untilBelow(limit, now);
    }

    private static Duration longest(Duration first, Duration second) {
        if (first == null) {
            return second;
        }
        return second == null || first.compareTo(second) >= 0 ? first : second;
    }

    private void maybeEvictExpired(Instant now) {
        if (now.isAfter(lastEviction.plus(limits.window()))) {
            lastEviction = now;
            tenants.values().removeIf(calls -> calls.count(now) == 0);
            actors.values().removeIf(calls -> calls.count(now) == 0);
            reported.values().removeIf(at -> !now.isBefore(at.plus(limits.window())));
        }
    }
}
