// ABOUTME: Idempotency keys scoped by (tenant, operation, key) with a transaction scoped advisory lock (ADR 0023 §4.2).
// ABOUTME: The lock serializes concurrent first requests; the unique row is the backstop.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessageOperation;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface MessageIdempotencyStore {

    record IdempotencyRecord(UUID id, UUID tenantId, MessageOperation operation, String idempotencyKey,
            String requestFingerprint, UUID messageId, UUID recordId, Instant createdAt) {
        public IdempotencyRecord {
            Objects.requireNonNull(id, "Record id is required");
            Objects.requireNonNull(tenantId, "Tenant id is required");
            Objects.requireNonNull(operation, "Operation is required");
            Objects.requireNonNull(idempotencyKey, "Idempotency key is required");
            Objects.requireNonNull(requestFingerprint, "Fingerprint is required");
            Objects.requireNonNull(messageId, "Message id is required");
            Objects.requireNonNull(createdAt, "Creation time is required");
            if ((operation == MessageOperation.SUBMIT) != (recordId == null)) {
                throw new IllegalArgumentException("Submit has no child record; every other operation has one");
            }
        }
    }

    /** pg_advisory_xact_lock on the tuple; released at commit or rollback. */
    void acquireLock(UUID tenantId, MessageOperation operation, String idempotencyKey);

    Optional<IdempotencyRecord> find(UUID tenantId, MessageOperation operation, String idempotencyKey);

    void record(IdempotencyRecord record);
}
