package io.github.stevdrey.dokene.purchase.persistence.jdbc;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.purchase.application.PurchaseConflictException;
import io.github.stevdrey.dokene.purchase.application.PurchaseCursor;
import io.github.stevdrey.dokene.purchase.application.PurchaseEventCursor;
import io.github.stevdrey.dokene.purchase.application.PurchaseRepository;
import io.github.stevdrey.dokene.purchase.domain.Purchase;
import io.github.stevdrey.dokene.purchase.domain.PurchaseEvent;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import io.github.stevdrey.dokene.purchase.domain.PurchaseStatus;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPurchaseRepository implements PurchaseRepository {
    private final JdbcTemplate jdbc;

    public JdbcPurchaseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public CreateResult insert(Purchase purchase, String key, String fingerprint, TenantContext actor) {
        int inserted = jdbc.update("""
                INSERT INTO dokene.purchases
                    (id, tenant_id, customer_id, purchased_at, description, status, created_at, updated_at,
                     version, idempotency_key, submission_fingerprint)
                VALUES (?, ?, ?, ?, ?, 'VALID', ?, ?, 0, ?, ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """, purchase.id().value(), purchase.tenantId().value(), purchase.customerId().value(),
                Timestamp.from(purchase.purchasedAt()), purchase.description(), Timestamp.from(purchase.createdAt()),
                Timestamp.from(purchase.updatedAt()), key, fingerprint);
        if (inserted == 1) {
            appendEvent(purchase, PurchaseEvent.Type.RECORDED, actor, purchase.createdAt());
            return new CreateResult(purchase, true, true);
        }
        Existing existing = jdbc.query("""
                SELECT p.* FROM dokene.purchases p
                WHERE tenant_id = ? AND idempotency_key = ?
                """, (row, index) -> new Existing(mapPurchase(row, index), row.getString("submission_fingerprint")),
                purchase.tenantId().value(), key).stream().findFirst().orElseThrow(PurchaseConflictException::new);
        return new CreateResult(existing.purchase(), false, existing.fingerprint().equals(fingerprint));
    }

    @Override
    public Optional<Purchase> findById(TenantId tenantId, CustomerId customerId, PurchaseId id) {
        return jdbc.query("SELECT * FROM dokene.purchases WHERE tenant_id = ? AND customer_id = ? AND id = ?",
                this::mapPurchase, tenantId.value(), customerId.value(), id.value()).stream().findFirst();
    }

    @Override
    public List<Purchase> list(TenantId tenantId, CustomerId customerId, PurchaseStatus status,
            PurchaseCursor before, int limit) {
        String statusSql = status == null ? "" : " AND status = ?";
        String cursorSql = before == null ? "" : " AND (purchased_at, id) < (?, ?)";
        String sql = "SELECT * FROM dokene.purchases WHERE tenant_id = ? AND customer_id = ?" + statusSql
                + cursorSql + " ORDER BY purchased_at DESC, id DESC LIMIT ?";
        var args = new ArrayList<Object>();
        args.add(tenantId.value()); args.add(customerId.value());
        if (status != null) args.add(status.name());
        if (before != null) { args.add(Timestamp.from(before.purchasedAt())); args.add(before.id()); }
        args.add(limit);
        return jdbc.query(sql, this::mapPurchase, args.toArray());
    }

    @Override
    public Optional<Purchase> lastValid(TenantId tenantId, CustomerId customerId) {
        return jdbc.query("""
                SELECT * FROM dokene.purchases
                WHERE tenant_id = ? AND customer_id = ? AND status = 'VALID'
                ORDER BY purchased_at DESC, id DESC LIMIT 1
                """, this::mapPurchase, tenantId.value(), customerId.value()).stream().findFirst();
    }

    @Override
    public void update(Purchase purchase, long expectedVersion, PurchaseEvent.Type eventType, TenantContext actor) {
        int changed = jdbc.update("""
                UPDATE dokene.purchases SET purchased_at = ?, description = ?, status = ?, updated_at = ?,
                    voided_at = ?, version = version + 1
                WHERE tenant_id = ? AND customer_id = ? AND id = ? AND version = ?
                """, Timestamp.from(purchase.purchasedAt()), purchase.description(), purchase.status().name(),
                Timestamp.from(purchase.updatedAt()), purchase.voidedAt() == null ? null : Timestamp.from(purchase.voidedAt()),
                purchase.tenantId().value(), purchase.customerId().value(), purchase.id().value(), expectedVersion);
        if (changed != 1) throw new PurchaseConflictException();
        purchase.synchronizeVersion(expectedVersion + 1);
        appendEvent(purchase, eventType, actor, purchase.updatedAt());
    }

    @Override
    public List<PurchaseEvent> history(Purchase purchase, PurchaseEventCursor before, int limit) {
        if (before == null) {
            return jdbc.query("""
                    SELECT * FROM dokene.purchase_history WHERE tenant_id = ? AND purchase_id = ?
                    ORDER BY occurred_at DESC, id DESC LIMIT ?
                    """, this::mapEvent, purchase.tenantId().value(), purchase.id().value(), limit);
        }
        return jdbc.query("""
                SELECT * FROM dokene.purchase_history WHERE tenant_id = ? AND purchase_id = ?
                    AND (occurred_at, id) < (?, ?)
                ORDER BY occurred_at DESC, id DESC LIMIT ?
                """, this::mapEvent, purchase.tenantId().value(), purchase.id().value(),
                Timestamp.from(before.occurredAt()), before.id(), limit);
    }

    private void appendEvent(Purchase purchase, PurchaseEvent.Type type, TenantContext actor, Instant at) {
        jdbc.update("""
                INSERT INTO dokene.purchase_history
                    (id, tenant_id, purchase_id, event_type, purchased_at, description, occurred_at,
                     actor_id, membership_id, purchase_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), purchase.tenantId().value(), purchase.id().value(), type.name(),
                Timestamp.from(purchase.purchasedAt()), purchase.description(), Timestamp.from(micros(at)),
                actor.identityId().value(), actor.membershipId().value(), purchase.version());
    }

    private Purchase mapPurchase(ResultSet row, int index) throws SQLException {
        Timestamp voided = row.getTimestamp("voided_at");
        return Purchase.restore(new PurchaseId(row.getObject("id", UUID.class)),
                new TenantId(row.getObject("tenant_id", UUID.class)),
                new CustomerId(row.getObject("customer_id", UUID.class)), row.getTimestamp("purchased_at").toInstant(),
                row.getString("description"), PurchaseStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                voided == null ? null : voided.toInstant(), row.getLong("version"));
    }

    private PurchaseEvent mapEvent(ResultSet row, int index) throws SQLException {
        return new PurchaseEvent(row.getObject("id", UUID.class), PurchaseEvent.Type.valueOf(row.getString("event_type")),
                row.getTimestamp("purchased_at").toInstant(), row.getString("description"),
                row.getTimestamp("occurred_at").toInstant(), new IdentityId(row.getObject("actor_id", UUID.class)),
                new TenantMembershipId(row.getObject("membership_id", UUID.class)), row.getLong("purchase_version"));
    }

    private static Instant micros(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private record Existing(Purchase purchase, String fingerprint) { }
}
