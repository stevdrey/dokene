package io.github.stevdrey.dokene.spike.cancellation;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * Spike prototype (Issue #180), test sources only. In-memory, per-instance registry of in-flight AI invocations.
 * <p>
 * The client-generated invocation id is only a lookup key: the full key also holds the tenant and actor taken from
 * the trusted server-side context, so an id alone never authorizes a cancel. An id is unique per (tenant, actor)
 * across operations, so {@code POST /api/ai-invocations/{id}/cancel} is unambiguous; the stored operation is checked
 * against the caller's current permissions. {@link #cancel} returns nothing, which makes "not found", "someone
 * else's", "not permitted" and "already finished" indistinguishable (no existence oracle).
 * <p>
 * An entry lives exactly as long as the request that owns it (registered before context assembly, closed in
 * {@code finally}), so a lost cancel leaves nothing behind and no live entry can expire. Capacity is reserved
 * atomically.
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
            if (entries.remove(key, this)) {
                capacity.release();
            }
        }
    }

    private final ConcurrentMap<Key, Registration> entries = new ConcurrentHashMap<>();
    private final Semaphore capacity;

    AiInvocationCancellationRegistry(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.capacity = new Semaphore(maxEntries);
    }

    /** Registers before the provider call starts; the caller closes the registration in a finally block. */
    Registration register(Key key, AiOperation operation) {
        Objects.requireNonNull(operation, "Operation is required");
        if (!capacity.tryAcquire()) {
            throw new RegistrationRejectedException("Too many in-flight AI invocations");
        }
        Registration registration = new Registration(key, operation);
        if (entries.putIfAbsent(key, registration) != null) {
            capacity.release();
            throw new RegistrationRejectedException("Duplicate AI invocation id");
        }
        return registration;
    }

    /**
     * Idempotent. Acts only on a live entry whose tenant, actor and id match and whose operation the caller is
     * currently permitted to perform.
     */
    void cancel(Key key, Set<AiOperation> permittedOperations) {
        Registration registration = entries.get(key);
        if (registration != null && permittedOperations.contains(registration.operation)) {
            registration.handle.cancel();
        }
    }

    int size() {
        return entries.size();
    }
}
