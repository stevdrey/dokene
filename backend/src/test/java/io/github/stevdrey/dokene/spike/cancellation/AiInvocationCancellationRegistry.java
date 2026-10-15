package io.github.stevdrey.dokene.spike.cancellation;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Spike prototype (Issue #180), test sources only. In-memory, per-instance registry of in-flight AI invocations.
 * <p>
 * The client-generated invocation id is only a lookup key: the full key also holds the tenant and actor taken from
 * the trusted server-side context, so an id alone never authorizes a cancel. An id is unique per (tenant, actor)
 * across operations, so {@code POST /api/ai-invocations/{id}/cancel} is unambiguous. The caller must currently hold
 * every permission the stored operation requires, the same conjunction its endpoint enforces. {@link #cancel}
 * returns nothing, which makes "not found", "someone else's", "not permitted" and "already finished"
 * indistinguishable (no existence oracle).
 * <p>
 * An entry lives exactly as long as the request that owns it (registered before context assembly, closed in
 * {@code finally}), so no live entry can expire. A cancel can overtake its own request on the network; when it finds
 * no entry it leaves a short-lived, bounded tombstone that a later {@link #register} with the same key consumes, so
 * that registration is born cancelled. {@code register} and {@code cancel} share one lock so the two steps cannot
 * interleave; both are O(1) apart from purging expired tombstones (amortized O(1), bounded by the cap).
 */
final class AiInvocationCancellationRegistry {

    record Key(TenantId tenantId, IdentityId actorId, UUID invocationId) {
        Key {
            Objects.requireNonNull(tenantId, "Tenant is required");
            Objects.requireNonNull(actorId, "Actor is required");
            Objects.requireNonNull(invocationId, "Invocation id is required");
        }
    }

    enum Outcome { CANCELLED, NOT_CANCELLED }

    /** Rejected registration: duplicate id for the same actor, or the registry is full. */
    static final class RegistrationRejectedException extends RuntimeException {
        RegistrationRejectedException(String message) {
            super(message);
        }
    }

    final class Registration implements AutoCloseable {
        private final Key key;
        private final AiOperation operation;
        private final AiCancellationHandle handle = new AiCancellationHandle();

        private Registration(Key key, AiOperation operation) {
            this.key = key;
            this.operation = operation;
        }

        AiCancellationHandle handle() {
            return handle;
        }

        /** Decided after the provider call returned or failed; {@code CANCELLED} only for a real abort. */
        Outcome outcome() {
            return handle.aborted() ? Outcome.CANCELLED : Outcome.NOT_CANCELLED;
        }

        @Override
        public void close() {
            synchronized (lock) {
                entries.remove(key, this);
            }
        }
    }

    /**
     * One permission snapshot per cancel request. The tombstone applies only if a single snapshot covers the
     * operation: a later, weaker cancel never removes an earlier authorized one, and separately insufficient cancels
     * never combine into an authorized one. Its expiry is fixed when it is created; repeated cancels never extend it.
     */
    private static final class Tombstone {
        private final List<Set<TenantPermission>> snapshots = new ArrayList<>();
        private final Instant expiresAt;

        private Tombstone(Set<TenantPermission> first, Instant expiresAt) {
            this.snapshots.add(first);
            this.expiresAt = expiresAt;
        }

        boolean authorizes(Set<TenantPermission> required) {
            return snapshots.stream().anyMatch(snapshot -> snapshot.containsAll(required));
        }

        void add(Set<TenantPermission> snapshot) {
            if (!snapshots.contains(snapshot) && snapshots.size() < MAX_SNAPSHOTS_PER_TOMBSTONE) {
                snapshots.add(snapshot);
            }
        }
    }

    /** Matches the recommendation service's maximum provider timeout; a tombstone outlives it by the margin. */
    static final Duration DEFAULT_PROVIDER_MAX_TIMEOUT = Duration.ofSeconds(30);
    static final Duration TOMBSTONE_MARGIN = Duration.ofSeconds(10);

    private static final int MAX_SNAPSHOTS_PER_TOMBSTONE = 4;

    private final Object lock = new Object();
    private final Map<Key, Registration> entries = new HashMap<>();
    /**
     * The only tombstone bookkeeping, so {@code maxTombstones} bounds all of it. Insertion order is creation order,
     * which is also expiry order because the TTL is constant and never extended; a consumed tombstone is removed
     * from the middle in O(1), and merging into an existing key keeps its position.
     */
    private final LinkedHashMap<Key, Tombstone> tombstones = new LinkedHashMap<>();
    private final int maxEntries;
    private final int maxTombstones;
    private final Duration tombstoneTtl;
    private final Clock clock;

    AiInvocationCancellationRegistry(int maxEntries) {
        this(maxEntries, maxEntries, DEFAULT_PROVIDER_MAX_TIMEOUT.plus(TOMBSTONE_MARGIN), Clock.systemUTC());
    }

    AiInvocationCancellationRegistry(int maxEntries, int maxTombstones, Duration tombstoneTtl, Clock clock) {
        if (maxEntries < 1 || maxTombstones < 1) {
            throw new IllegalArgumentException("Capacities must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxTombstones = maxTombstones;
        this.tombstoneTtl = Objects.requireNonNull(tombstoneTtl, "Tombstone TTL is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /** Registers before the provider call starts; the caller closes the registration in a finally block. */
    Registration register(Key key, AiOperation operation) {
        Objects.requireNonNull(operation, "Operation is required");
        synchronized (lock) {
            if (entries.size() >= maxEntries) {
                throw new RegistrationRejectedException("Too many in-flight AI invocations");
            }
            if (entries.containsKey(key)) {
                throw new RegistrationRejectedException("Duplicate AI invocation id");
            }
            Registration registration = new Registration(key, operation);
            entries.put(key, registration);
            Tombstone tombstone = tombstones.remove(key);
            if (tombstone != null && tombstone.expiresAt.isAfter(clock.instant())
                    && tombstone.authorizes(requiredPermissions(operation))) {
                registration.handle.cancel();
            }
            return registration;
        }
    }

    /**
     * Idempotent. Acts only on an entry whose tenant, actor and id match, when the caller currently holds every
     * permission required to perform the stored operation. Without a live entry it leaves a tombstone, checked
     * against the operation when the request registers. A full tombstone store drops the cancel (today's behaviour).
     */
    void cancel(Key key, Set<TenantPermission> callerPermissions) {
        AiCancellationHandle toCancel = null;
        synchronized (lock) {
            Registration registration = entries.get(key);
            if (registration != null) {
                if (callerPermissions.containsAll(requiredPermissions(registration.operation))) {
                    toCancel = registration.handle;
                }
            } else {
                Instant now = clock.instant();
                purgeExpired(now);
                Set<TenantPermission> snapshot = Set.copyOf(callerPermissions);
                Tombstone existing = tombstones.get(key);
                if (existing != null) {
                    existing.add(snapshot);
                } else if (tombstones.size() < maxTombstones) {
                    Tombstone created = new Tombstone(snapshot, now.plus(tombstoneTtl));
                    tombstones.put(key, created);
                }
            }
        }
        if (toCancel != null) {
            toCancel.cancel();
        }
    }

    /**
     * Removes expired tombstones from the oldest end and stops at the first live one. Each tombstone is removed at
     * most once, so the cost is amortized O(1) per cancel and never exceeds {@code maxTombstones} in one call.
     */
    private void purgeExpired(Instant now) {
        Iterator<Tombstone> oldestFirst = tombstones.values().iterator();
        while (oldestFirst.hasNext() && !oldestFirst.next().expiresAt.isAfter(now)) {
            oldestFirst.remove();
        }
    }

    /** Mirrors the endpoints: recommendation needs FOLLOWUP_EVALUATE; draft needs MESSAGE_DRAFT and FOLLOWUP_EVALUATE. */
    private Set<TenantPermission> requiredPermissions(AiOperation operation) {
        return switch (operation) {
            case NEXT_BEST_ACTION -> EnumSet.of(TenantPermission.FOLLOWUP_EVALUATE);
            case MESSAGE_DRAFT -> EnumSet.of(TenantPermission.MESSAGE_DRAFT, TenantPermission.FOLLOWUP_EVALUATE);
        };
    }

    int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    int tombstones() {
        synchronized (lock) {
            return tombstones.size();
        }
    }
}
