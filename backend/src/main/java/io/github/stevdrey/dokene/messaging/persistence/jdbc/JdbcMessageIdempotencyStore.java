// ABOUTME: JDBC idempotency store: a transaction scoped advisory lock plus the unique key row (ADR 0023 §4.2).
// ABOUTME: The lock hashes tenant and operation in one slot and the key in the other.
package io.github.stevdrey.dokene.messaging.persistence.jdbc;

import io.github.stevdrey.dokene.messaging.application.MessageIdempotencyStore;
import io.github.stevdrey.dokene.messaging.domain.MessageOperation;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMessageIdempotencyStore implements MessageIdempotencyStore {
    private final JdbcTemplate jdbc;

    public JdbcMessageIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void acquireLock(UUID tenantId, MessageOperation operation, String idempotencyKey) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))",
                tenantId + ":" + operation.name(), idempotencyKey);
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID tenantId, MessageOperation operation, String idempotencyKey) {
        List<IdempotencyRecord> rows = jdbc.query("""
                SELECT * FROM dokene.outbound_message_idempotency_keys
                WHERE tenant_id = ? AND operation = ? AND idempotency_key = ?
                """, (row, index) -> new IdempotencyRecord(row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class), MessageOperation.valueOf(row.getString("operation")),
                        row.getString("idempotency_key"), row.getString("request_fingerprint"),
                        row.getObject("message_id", UUID.class), row.getObject("record_id", UUID.class),
                        row.getTimestamp("created_at").toInstant()), tenantId, operation.name(), idempotencyKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public void record(IdempotencyRecord r) {
        jdbc.update("""
                INSERT INTO dokene.outbound_message_idempotency_keys
                    (id, tenant_id, operation, idempotency_key, request_fingerprint, message_id, record_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, r.id(), r.tenantId(), r.operation().name(), r.idempotencyKey(), r.requestFingerprint(),
                r.messageId(), r.recordId(), Timestamp.from(r.createdAt()));
    }
}
