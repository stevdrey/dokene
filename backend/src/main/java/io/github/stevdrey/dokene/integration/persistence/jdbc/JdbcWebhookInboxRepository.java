package io.github.stevdrey.dokene.integration.persistence.jdbc;

import io.github.stevdrey.dokene.integration.application.VerifiedWebhookEvent.Provider;
import io.github.stevdrey.dokene.integration.application.WebhookInboxRepository;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped statements still name the tenant explicitly, so a wrong tenant scope finds nothing rather than relying
 * on RLS alone. The dedup insert uses ON CONFLICT DO NOTHING: a key held by another tenant reads exactly like a
 * duplicate and reveals nothing about that tenant.
 */
@Repository
public class JdbcWebhookInboxRepository implements WebhookInboxRepository {
    private final JdbcTemplate jdbc;

    public JdbcWebhookInboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertPending(InboxEvent event) {
        int inserted = jdbc.update("""
                INSERT INTO dokene.provider_webhook_events (id, tenant_id, provider, provider_event_id,
                    provider_message_id, delivery_status, occurred_at, failure_category, state, attempts,
                    received_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                ON CONFLICT (provider, provider_event_id) DO NOTHING
                """, event.id(), event.tenantId().value(), event.provider().name(), event.providerEventId(),
                event.providerMessageId(), event.status().name(), Timestamp.from(event.occurredAt()),
                event.failure().map(Enum::name).orElse(null), Timestamp.from(event.receivedAt()),
                Timestamp.from(event.receivedAt()));
        return inserted == 1;
    }

    @Override
    public Optional<InboxEvent> lockPending(TenantId tenantId, UUID id) {
        return jdbc.query("""
                SELECT * FROM dokene.provider_webhook_events
                WHERE tenant_id = ? AND id = ? AND state = 'PENDING'
                FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new InboxEvent(rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)), Provider.valueOf(rs.getString("provider")),
                rs.getString("provider_event_id"), rs.getString("provider_message_id"),
                DeliveryStatus.valueOf(rs.getString("delivery_status")), instant(rs, "occurred_at"),
                Optional.ofNullable(rs.getString("failure_category")).map(FailureCategory::valueOf),
                rs.getInt("attempts"), instant(rs, "received_at")), tenantId.value(), id).stream().findFirst();
    }

    @Override
    public void markProcessed(TenantId tenantId, UUID id, InboxState state, Instant processedAt) {
        if (state != InboxState.APPLIED && state != InboxState.IGNORED) {
            throw new IllegalArgumentException("Only APPLIED or IGNORED mark a processed event");
        }
        jdbc.update("""
                UPDATE dokene.provider_webhook_events SET state = ?, processed_at = ?, attempts = attempts + 1
                WHERE tenant_id = ? AND id = ? AND state = 'PENDING'
                """, state.name(), Timestamp.from(processedAt), tenantId.value(), id);
    }

    @Override
    public void markUnmatched(TenantId tenantId, UUID id, Instant processedAt) {
        jdbc.update("""
                UPDATE dokene.provider_webhook_events
                SET state = 'UNMATCHED', provider_message_id = NULL, processed_at = ?, attempts = attempts + 1
                WHERE tenant_id = ? AND id = ? AND state = 'PENDING'
                """, Timestamp.from(processedAt), tenantId.value(), id);
    }

    @Override
    public void reschedule(TenantId tenantId, UUID id, Instant now, Duration base, Duration cap) {
        jdbc.update("""
                UPDATE dokene.provider_webhook_events
                SET attempts = attempts + 1,
                    next_attempt_at = ? + LEAST(make_interval(secs => ? * power(2, LEAST(attempts, 16))),
                        make_interval(secs => ?))
                WHERE tenant_id = ? AND id = ? AND state = 'PENDING'
                """, Timestamp.from(now), (double) base.toSeconds(), (double) cap.toSeconds(), tenantId.value(), id);
    }

    @Override
    public List<DueEvent> findDue(Instant dueAt, int limit) {
        return jdbc.query("SELECT tenant_id, event_id FROM dokene.find_due_webhook_events(?, ?)",
                (rs, row) -> new DueEvent(new TenantId(rs.getObject("tenant_id", UUID.class)),
                        rs.getObject("event_id", UUID.class)), Timestamp.from(dueAt), limit);
    }

    @Override
    public int purgeProcessedBefore(Instant processedBefore, int limit) {
        Integer purged = jdbc.queryForObject("SELECT dokene.purge_webhook_events(?, ?)", Integer.class,
                Timestamp.from(processedBefore), limit);
        return purged == null ? 0 : purged;
    }

    @Override
    public <T> Optional<T> runAsSoleSweeper(Supplier<T> run) {
        return jdbc.execute((ConnectionCallback<Optional<T>>) connection -> {
            if (!sweeperLock(connection, "pg_try_advisory_lock")) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(run.get());
            } finally {
                sweeperLock(connection, "pg_advisory_unlock");
            }
        });
    }

    private static boolean sweeperLock(Connection connection, String function) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + function + "(hashtext('dokene'), hashtext('WEBHOOK_INBOX'))");
                ResultSet result = statement.executeQuery()) {
            return result.next() && result.getBoolean(1);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}
