package io.github.stevdrey.dokene.spike.cancellation;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Spike prototype (Issue #180), test sources only. In-memory, per-instance registry of in-flight AI invocations.
 * <p>
 * The client-generated invocation id is only a lookup key: the full key also holds the tenant, actor and operation
 * taken from the trusted server-side context, so an id alone never authorizes a cancel. {@link #cancel} returns
 * nothing, which makes "not found", "someone else's" and "already finished" indistinguishable (no existence oracle).
 */
final class AiInvocationCancellationRegistry {

    record Key(TenantId tenantId, IdentityId actorId, AiOperation operation, UUID invocationId) {
        Key {
            Objects.requireNonNull(tenantId, "Tenant is required");
            Objects.requireNonNull(actorId, "Actor is required");
            Objects.requireNonNull(operation, "Operation is required");
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
        private final AiCancellationHandle handle = new AiCancellationHandle();
        private final Instant expiresAt;

        private Registration(Key key, Instant expiresAt) {
            this.key = key;
            this.expiresAt = expiresAt;
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
            entries.remove(key, this);
        }
    }

    private final ConcurrentMap<Key, Registration> entries = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final Duration ttl;
    private final Clock clock;

    AiInvocationCancellationRegistry(int maxEntries, Duration ttl, Clock clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.ttl = Objects.requireNonNull(ttl, "TTL is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /** Registers before the provider call starts; the caller closes the registration in a finally block. */
    Registration register(Key key) {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !entry.expiresAt.isAfter(now));
        if (entries.size() >= maxEntries) {
            throw new RegistrationRejectedException("Too many in-flight AI invocations");
        }
        Registration registration = new Registration(key, now.plus(ttl));
        if (entries.putIfAbsent(key, registration) != null) {
            throw new RegistrationRejectedException("Duplicate AI invocation id");
        }
        return registration;
    }

    /** Idempotent. Acts only on a live entry whose tenant, actor, operation and id all match. */
    void cancel(Key key) {
        Registration registration = entries.get(key);
        if (registration != null && registration.expiresAt.isAfter(clock.instant())) {
            registration.handle.cancel();
        }
    }

    int size() {
        return entries.size();
    }
}
