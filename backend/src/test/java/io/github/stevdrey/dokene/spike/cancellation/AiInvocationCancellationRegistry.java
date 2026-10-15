package io.github.stevdrey.dokene.spike.cancellation;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
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
 * interleave; both are O(1) apart from purging expired tombstones.
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

    private record Tombstone(Set<TenantPermission> callerPermissions, Instant expiresAt) {
    }

    private final Object lock = new Object();
    private final Map<Key, Registration> entries = new HashMap<>();
    private final Map<Key, Tombstone> tombstones = new HashMap<>();
    private final int maxEntries;
    private final int maxTombstones;
    private final Duration tombstoneTtl;
    private final Clock clock;

    AiInvocationCancellationRegistry(int maxEntries) {
        this(maxEntries, maxEntries, Duration.ofSeconds(30), Clock.systemUTC());
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
            if (tombstone != null && tombstone.expiresAt().isAfter(clock.instant())
                    && tombstone.callerPermissions().containsAll(requiredPermissions(operation))) {
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
                tombstones.values().removeIf(tombstone -> !tombstone.expiresAt().isAfter(now));
                Tombstone existing = tombstones.get(key);
                if (existing != null) {
                    // A later, weaker cancel never downgrades an earlier authorized one.
                    Set<TenantPermission> merged = EnumSet.noneOf(TenantPermission.class);
                    merged.addAll(existing.callerPermissions());
                    merged.addAll(callerPermissions);
                    tombstones.put(key, new Tombstone(Set.copyOf(merged), now.plus(tombstoneTtl)));
                } else if (tombstones.size() < maxTombstones) {
                    tombstones.put(key, new Tombstone(Set.copyOf(callerPermissions), now.plus(tombstoneTtl)));
                }
            }
        }
        if (toCancel != null) {
            toCancel.cancel();
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
