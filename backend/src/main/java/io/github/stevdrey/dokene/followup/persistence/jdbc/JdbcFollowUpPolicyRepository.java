package io.github.stevdrey.dokene.followup.persistence.jdbc;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpConflictException;
import io.github.stevdrey.dokene.followup.application.FollowUpDismissalResult;
import io.github.stevdrey.dokene.followup.application.FollowUpPolicyRepository;
import io.github.stevdrey.dokene.followup.application.FollowUpQueueCursor;
import io.github.stevdrey.dokene.followup.application.FollowUpQueuePage;
import io.github.stevdrey.dokene.followup.application.FollowUpQueueQuery;
import io.github.stevdrey.dokene.followup.application.ManualFollowUpResult;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.FollowUpDismissal;
import io.github.stevdrey.dokene.followup.domain.FollowUpQueueItem;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.followup.domain.ManualFollowUpCompletion;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import java.util.List;
import java.util.Optional;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFollowUpPolicyRepository implements FollowUpPolicyRepository {
    private final JdbcTemplate jdbc;

    public JdbcFollowUpPolicyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public TenantFollowUpPolicy tenantPolicy(TenantId tenantId) {
        return jdbc.queryForObject("""
                SELECT cadence_days, time_zone, version FROM dokene.tenant_follow_up_policies WHERE tenant_id = ?
                """, (row, index) -> new TenantFollowUpPolicy(tenantId, row.getInt("cadence_days"),
                        ZoneId.of(row.getString("time_zone")), row.getLong("version")), tenantId.value());
    }

    @Override
    public CustomerFollowUpPolicy customerPolicy(TenantId tenantId, CustomerId customerId) {
        return jdbc.queryForObject("""
                SELECT cadence_days, explicit_next_date, snoozed_until, last_manual_follow_up_date,
                       last_dismissed_date, version
                FROM dokene.customer_follow_up_policies WHERE tenant_id = ? AND customer_id = ?
                """, (row, index) -> new CustomerFollowUpPolicy(tenantId, customerId,
                        (Integer) row.getObject("cadence_days"), localDate(row.getDate("explicit_next_date")),
                        localDate(row.getDate("snoozed_until")), localDate(row.getDate("last_manual_follow_up_date")),
                        localDate(row.getDate("last_dismissed_date")),
                        row.getLong("version")), tenantId.value(), customerId.value());
    }

    @Override
    public TenantFollowUpPolicy updateTenantPolicy(TenantFollowUpPolicy policy, long expectedVersion) {
        long nextVersion = nextVersion(expectedVersion);
        int updated = jdbc.update("""
                UPDATE dokene.tenant_follow_up_policies
                SET cadence_days = ?, time_zone = ?, version = ? WHERE tenant_id = ? AND version = ?
                """, policy.cadenceDays(), policy.zoneId().getId(), nextVersion,
                policy.tenantId().value(), expectedVersion);
        requireUpdated(updated);
        return tenantPolicy(policy.tenantId());
    }

    @Override
    public CustomerFollowUpPolicy updateCustomerPolicy(TenantId tenantId, CustomerId customerId,
            Integer cadenceDays, LocalDate explicitNextDate, long expectedVersion) {
        long nextVersion = nextVersion(expectedVersion);
        int updated = jdbc.update("""
                UPDATE dokene.customer_follow_up_policies
                SET cadence_days = ?, explicit_next_date = ?, version = ?
                WHERE tenant_id = ? AND customer_id = ? AND version = ?
                """, cadenceDays, sqlDate(explicitNextDate), nextVersion, tenantId.value(), customerId.value(),
                expectedVersion);
        requireUpdated(updated);
        return customerPolicy(tenantId, customerId);
    }

    @Override
    public FollowUpQueuePage findDueQueue(TenantId tenantId, FollowUpQueueQuery query, Instant evaluatedAt) {
        var params = new java.util.ArrayList<Object>();
        params.add(tenantId.value());
        params.add(Timestamp.from(evaluatedAt));
        params.add(tenantId.value());

        var sql = new StringBuilder("""
                WITH tenant_policy AS (
                    SELECT cadence_days, time_zone
                    FROM dokene.tenant_follow_up_policies
                    WHERE tenant_id = ?
                ),
                raw_candidates AS (
                    SELECT
                        c.id AS customer_id,
                        c.display_name,
                        (SELECT p.normalized_phone FROM dokene.customer_phone_contacts p
                         WHERE p.tenant_id = c.tenant_id AND p.customer_id = c.id AND p.is_primary = true LIMIT 1) AS primary_phone,
                        cfp.version AS policy_version,
                        COALESCE(cfp.cadence_days, tp.cadence_days) AS effective_cadence,
                        cfp.snoozed_until,
                        cfp.explicit_next_date,
                        cfp.last_manual_follow_up_date,
                        cfp.last_dismissed_date,
                        lp.last_purchase_at,
                        (lp.last_purchase_at AT TIME ZONE tp.time_zone)::date AS last_purchase_date,
                        (? AT TIME ZONE tp.time_zone)::date AS tenant_today
                    FROM dokene.customers c
                    JOIN tenant_policy tp ON true
                    JOIN dokene.customer_follow_up_policies cfp ON cfp.tenant_id = c.tenant_id AND cfp.customer_id = c.id
                    LEFT JOIN LATERAL (
                        SELECT pur.purchased_at AS last_purchase_at
                        FROM dokene.purchases pur
                        WHERE pur.tenant_id = c.tenant_id AND pur.customer_id = c.id AND pur.status = 'VALID'
                        ORDER BY pur.purchased_at DESC, pur.id DESC
                        LIMIT 1
                    ) lp ON true
                    WHERE c.tenant_id = ?
                      AND c.status = 'ACTIVE'
                      AND NOT EXISTS (
                          SELECT 1 FROM dokene.customer_do_not_contact dnc
                          WHERE dnc.tenant_id = c.tenant_id AND dnc.customer_id = c.id AND dnc.enabled = true
                      )
                      AND EXISTS (
                          SELECT 1 FROM dokene.customer_phone_contacts phone
                          JOIN dokene.customer_contact_consents cc
                            ON cc.tenant_id = phone.tenant_id AND cc.customer_id = phone.customer_id AND cc.phone_contact_id = phone.id
                          WHERE phone.tenant_id = c.tenant_id AND phone.customer_id = c.id
                            AND cc.channel = 'WHATSAPP' AND cc.status = 'GRANTED'
                      )
                ),
                evaluated_candidates AS (
                    SELECT
                        customer_id,
                        display_name,
                        primary_phone,
                        policy_version,
                        effective_cadence,
                        last_purchase_at,
                        last_manual_follow_up_date,
                        last_dismissed_date,
                        tenant_today,
                        CASE
                            WHEN snoozed_until IS NOT NULL AND snoozed_until >= tenant_today THEN snoozed_until
                            WHEN explicit_next_date IS NOT NULL THEN explicit_next_date
                            WHEN (
                                (last_manual_follow_up_date IS NOT NULL AND (last_dismissed_date IS NULL OR last_manual_follow_up_date >= last_dismissed_date) AND (last_purchase_date IS NULL OR last_manual_follow_up_date >= last_purchase_date))
                            ) THEN last_manual_follow_up_date + effective_cadence
                            WHEN (
                                (last_dismissed_date IS NOT NULL AND (last_manual_follow_up_date IS NULL OR last_dismissed_date > last_manual_follow_up_date) AND (last_purchase_date IS NULL OR last_dismissed_date >= last_purchase_date))
                            ) THEN last_dismissed_date + effective_cadence
                            WHEN last_purchase_date IS NOT NULL THEN last_purchase_date + effective_cadence
                            ELSE NULL
                        END AS due_date,
                        CASE
                            WHEN snoozed_until IS NOT NULL AND snoozed_until >= tenant_today THEN 'SNOOZE'
                            WHEN explicit_next_date IS NOT NULL THEN 'EXPLICIT_DATE'
                            WHEN (
                                (last_manual_follow_up_date IS NOT NULL AND (last_dismissed_date IS NULL OR last_manual_follow_up_date >= last_dismissed_date) AND (last_purchase_date IS NULL OR last_manual_follow_up_date >= last_purchase_date))
                            ) THEN 'LAST_MANUAL_FOLLOW_UP'
                            WHEN (
                                (last_dismissed_date IS NOT NULL AND (last_manual_follow_up_date IS NULL OR last_dismissed_date > last_manual_follow_up_date) AND (last_purchase_date IS NULL OR last_dismissed_date >= last_purchase_date))
                            ) THEN 'LAST_DISMISSAL'
                            WHEN last_purchase_date IS NOT NULL THEN 'LAST_PURCHASE'
                            ELSE 'NONE'
                        END AS timing_source
                    FROM raw_candidates
                )
                SELECT
                    customer_id,
                    display_name,
                    primary_phone,
                    policy_version,
                    effective_cadence,
                    last_purchase_at,
                    last_manual_follow_up_date,
                    last_dismissed_date,
                    due_date,
                    timing_source,
                    CASE WHEN due_date = tenant_today THEN 'DUE' ELSE 'OVERDUE' END AS status,
                    CASE WHEN due_date = tenant_today THEN 'DUE_TODAY' ELSE 'OVERDUE' END AS reason
                FROM evaluated_candidates
                WHERE due_date IS NOT NULL
                  AND due_date <= tenant_today
                """);

        if (query.statusFilter() == io.github.stevdrey.dokene.followup.domain.FollowUpStatus.DUE) {
            sql.append(" AND due_date = tenant_today");
        } else if (query.statusFilter() == io.github.stevdrey.dokene.followup.domain.FollowUpStatus.OVERDUE) {
            sql.append(" AND due_date < tenant_today");
        }

        if (query.cursor() != null) {
            sql.append(" AND (due_date > ? OR (due_date = ? AND customer_id > ?))");
            params.add(sqlDate(query.cursor().dueDate()));
            params.add(sqlDate(query.cursor().dueDate()));
            params.add(query.cursor().customerId());
        }

        sql.append(" ORDER BY due_date ASC, customer_id ASC LIMIT ?");
        params.add(query.limit() + 1);

        List<io.github.stevdrey.dokene.followup.domain.FollowUpQueueItem> rows = jdbc.query(
                sql.toString(),
                (rs, rowNum) -> {
                    var customerId = new CustomerId(rs.getObject("customer_id", UUID.class));
                    var displayName = rs.getString("display_name");
                    var primaryPhone = rs.getString("primary_phone");
                    var policyVersion = rs.getLong("policy_version");
                    var effectiveCadence = rs.getInt("effective_cadence");
                    Timestamp ts = rs.getTimestamp("last_purchase_at");
                    Instant lastPurchaseAt = ts == null ? null : ts.toInstant();
                    LocalDate lastManualDate = localDate(rs.getDate("last_manual_follow_up_date"));
                    LocalDate lastDismissedDate = localDate(rs.getDate("last_dismissed_date"));
                    LocalDate dueDate = rs.getDate("due_date").toLocalDate();
                    var timingSource = io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource.valueOf(
                            rs.getString("timing_source"));
                    var status = io.github.stevdrey.dokene.followup.domain.FollowUpStatus.valueOf(
                            rs.getString("status"));
                    var reason = io.github.stevdrey.dokene.followup.domain.FollowUpReason.valueOf(
                            rs.getString("reason"));
                    return new io.github.stevdrey.dokene.followup.domain.FollowUpQueueItem(
                            customerId, displayName, primaryPhone, status, List.of(reason), dueDate,
                            timingSource, policyVersion, effectiveCadence, lastPurchaseAt, lastManualDate,
                            lastDismissedDate, evaluatedAt);
                },
                params.toArray());

        if (rows.size() > query.limit()) {
            var pageItems = rows.subList(0, query.limit());
            var lastItem = pageItems.getLast();
            var nextCursor = new FollowUpQueueCursor(lastItem.dueDate(), lastItem.customerId().value()).encode();
            return new FollowUpQueuePage(pageItems, nextCursor);
        } else {
            return new FollowUpQueuePage(rows, null);
        }
    }

    @Override
    public ManualFollowUpResult recordManualFollowUp(TenantId tenantId, CustomerId customerId, LocalDate date,
            long expectedVersion, String idempotencyKey, Instant occurredAt, IdentityId actorId,
            TenantMembershipId membershipId, String notes) {
        UUID completionId = UUID.randomUUID();
        long nextVersion = nextVersion(expectedVersion);
        var inserted = jdbc.query("""
                INSERT INTO dokene.manual_follow_up_completions
                    (id, tenant_id, customer_id, idempotency_key, completed_on, policy_version,
                     occurred_at, actor_id, membership_id, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                RETURNING *
                """, this::mapCompletion, completionId, tenantId.value(), customerId.value(), idempotencyKey,
                sqlDate(date), nextVersion, Timestamp.from(occurredAt), actorId.value(), membershipId.value(), notes);
        if (inserted.isEmpty()) {
            ManualFollowUpCompletion existing = completion(tenantId, idempotencyKey);
            if (!existing.customerId().equals(customerId)) throw new FollowUpConflictException();
            return new ManualFollowUpResult(existing, false);
        }
        int updated = jdbc.update("""
                UPDATE dokene.customer_follow_up_policies
                SET explicit_next_date = NULL, snoozed_until = NULL, last_manual_follow_up_date = ?, version = ?
                WHERE tenant_id = ? AND customer_id = ? AND version = ?
                  AND EXISTS (
                      SELECT 1 FROM dokene.customers c
                      WHERE c.tenant_id = customer_follow_up_policies.tenant_id
                        AND c.id = customer_follow_up_policies.customer_id
                        AND c.status = 'ACTIVE'
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM dokene.customer_do_not_contact dnc
                      WHERE dnc.tenant_id = customer_follow_up_policies.tenant_id
                        AND dnc.customer_id = customer_follow_up_policies.customer_id
                        AND dnc.enabled = true
                  )
                  AND EXISTS (
                      SELECT 1 FROM dokene.customer_phone_contacts phone
                      JOIN dokene.customer_contact_consents cc
                        ON cc.tenant_id = phone.tenant_id AND cc.customer_id = phone.customer_id AND cc.phone_contact_id = phone.id
                      WHERE phone.tenant_id = customer_follow_up_policies.tenant_id
                        AND phone.customer_id = customer_follow_up_policies.customer_id
                        AND cc.channel = 'WHATSAPP' AND cc.status = 'GRANTED'
                  )
                """, sqlDate(date), nextVersion, tenantId.value(), customerId.value(), expectedVersion);
        requireUpdated(updated);
        return new ManualFollowUpResult(inserted.getFirst(), true);
    }

    @Override
    public FollowUpDismissalResult recordDismissal(TenantId tenantId, CustomerId customerId, LocalDate date,
            long expectedVersion, String idempotencyKey, Instant occurredAt, IdentityId actorId,
            TenantMembershipId membershipId, String notes) {
        UUID dismissalId = UUID.randomUUID();
        long nextVersion = nextVersion(expectedVersion);
        var inserted = jdbc.query("""
                INSERT INTO dokene.follow_up_dismissals
                    (id, tenant_id, customer_id, idempotency_key, dismissed_on, policy_version,
                     occurred_at, actor_id, membership_id, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                RETURNING *
                """, this::mapDismissal, dismissalId, tenantId.value(), customerId.value(), idempotencyKey,
                sqlDate(date), nextVersion, Timestamp.from(occurredAt), actorId.value(), membershipId.value(), notes);
        if (inserted.isEmpty()) {
            io.github.stevdrey.dokene.followup.domain.FollowUpDismissal existing = dismissal(tenantId, idempotencyKey);
            if (!existing.customerId().equals(customerId)) throw new FollowUpConflictException();
            return new FollowUpDismissalResult(existing, false);
        }
        int updated = jdbc.update("""
                UPDATE dokene.customer_follow_up_policies
                SET explicit_next_date = NULL, snoozed_until = NULL, last_dismissed_date = ?, version = ?
                WHERE tenant_id = ? AND customer_id = ? AND version = ?
                  AND EXISTS (
                      SELECT 1 FROM dokene.customers c
                      WHERE c.tenant_id = customer_follow_up_policies.tenant_id
                        AND c.id = customer_follow_up_policies.customer_id
                        AND c.status = 'ACTIVE'
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM dokene.customer_do_not_contact dnc
                      WHERE dnc.tenant_id = customer_follow_up_policies.tenant_id
                        AND dnc.customer_id = customer_follow_up_policies.customer_id
                        AND dnc.enabled = true
                  )
                  AND EXISTS (
                      SELECT 1 FROM dokene.customer_phone_contacts phone
                      JOIN dokene.customer_contact_consents cc
                        ON cc.tenant_id = phone.tenant_id AND cc.customer_id = phone.customer_id AND cc.phone_contact_id = phone.id
                      WHERE phone.tenant_id = customer_follow_up_policies.tenant_id
                        AND phone.customer_id = customer_follow_up_policies.customer_id
                        AND cc.channel = 'WHATSAPP' AND cc.status = 'GRANTED'
                  )
                """, sqlDate(date), nextVersion, tenantId.value(), customerId.value(), expectedVersion);
        requireUpdated(updated);
        return new FollowUpDismissalResult(inserted.getFirst(), true);
    }

    @Override
    public CustomerFollowUpPolicy snooze(TenantId tenantId, CustomerId customerId, LocalDate until,
            long expectedVersion) {
        long nextVersion = nextVersion(expectedVersion);
        int updated = jdbc.update("""
                UPDATE dokene.customer_follow_up_policies
                SET snoozed_until = ?, version = ?
                WHERE tenant_id = ? AND customer_id = ? AND version = ?
                  AND EXISTS (
                      SELECT 1 FROM dokene.customers c
                      WHERE c.tenant_id = customer_follow_up_policies.tenant_id
                        AND c.id = customer_follow_up_policies.customer_id
                        AND c.status = 'ACTIVE'
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM dokene.customer_do_not_contact dnc
                      WHERE dnc.tenant_id = customer_follow_up_policies.tenant_id
                        AND dnc.customer_id = customer_follow_up_policies.customer_id
                        AND dnc.enabled = true
                  )
                  AND EXISTS (
                      SELECT 1 FROM dokene.customer_phone_contacts phone
                      JOIN dokene.customer_contact_consents cc
                        ON cc.tenant_id = phone.tenant_id AND cc.customer_id = phone.customer_id AND cc.phone_contact_id = phone.id
                      WHERE phone.tenant_id = customer_follow_up_policies.tenant_id
                        AND phone.customer_id = customer_follow_up_policies.customer_id
                        AND cc.channel = 'WHATSAPP' AND cc.status = 'GRANTED'
                  )
                """, sqlDate(until), nextVersion, tenantId.value(), customerId.value(), expectedVersion);
        requireUpdated(updated);
        return customerPolicy(tenantId, customerId);
    }

    @Override
    public Optional<ManualFollowUpCompletion> findCompletion(TenantId tenantId, String idempotencyKey) {
        var list = jdbc.query("""
                SELECT * FROM dokene.manual_follow_up_completions WHERE tenant_id = ? AND idempotency_key = ?
                """, this::mapCompletion, tenantId.value(), idempotencyKey);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    @Override
    public Optional<FollowUpDismissal> findDismissal(TenantId tenantId, String idempotencyKey) {
        var list = jdbc.query("""
                SELECT * FROM dokene.follow_up_dismissals WHERE tenant_id = ? AND idempotency_key = ?
                """, this::mapDismissal, tenantId.value(), idempotencyKey);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    private ManualFollowUpCompletion completion(TenantId tenantId, String key) {
        return jdbc.queryForObject("""
                SELECT * FROM dokene.manual_follow_up_completions WHERE tenant_id = ? AND idempotency_key = ?
                """, this::mapCompletion, tenantId.value(), key);
    }

    private FollowUpDismissal dismissal(TenantId tenantId, String key) {
        return jdbc.queryForObject("""
                SELECT * FROM dokene.follow_up_dismissals WHERE tenant_id = ? AND idempotency_key = ?
                """, this::mapDismissal, tenantId.value(), key);
    }

    private ManualFollowUpCompletion mapCompletion(ResultSet row, int index) throws SQLException {
        return new ManualFollowUpCompletion(row.getObject("id", UUID.class),
                new TenantId(row.getObject("tenant_id", UUID.class)),
                new CustomerId(row.getObject("customer_id", UUID.class)), row.getDate("completed_on").toLocalDate(),
                row.getLong("policy_version"), row.getTimestamp("occurred_at").toInstant(),
                new IdentityId(row.getObject("actor_id", UUID.class)),
                new TenantMembershipId(row.getObject("membership_id", UUID.class)),
                row.getString("notes"));
    }

    private io.github.stevdrey.dokene.followup.domain.FollowUpDismissal mapDismissal(ResultSet row, int index)
            throws SQLException {
        return new io.github.stevdrey.dokene.followup.domain.FollowUpDismissal(row.getObject("id", UUID.class),
                new TenantId(row.getObject("tenant_id", UUID.class)),
                new CustomerId(row.getObject("customer_id", UUID.class)), row.getDate("dismissed_on").toLocalDate(),
                row.getLong("policy_version"), row.getTimestamp("occurred_at").toInstant(),
                new IdentityId(row.getObject("actor_id", UUID.class)),
                new TenantMembershipId(row.getObject("membership_id", UUID.class)),
                row.getString("notes"));
    }

    private void requireUpdated(int updated) { if (updated != 1) throw new FollowUpConflictException(); }
    private long nextVersion(long current) {
        try {
            return Math.incrementExact(current);
        } catch (ArithmeticException exception) {
            throw new FollowUpConflictException();
        }
    }
    private LocalDate localDate(Date value) { return value == null ? null : value.toLocalDate(); }
    private Date sqlDate(LocalDate value) { return value == null ? null : Date.valueOf(value); }
}
