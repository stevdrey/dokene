package io.github.stevdrey.dokene.followup.persistence.jdbc;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpConflictException;
import io.github.stevdrey.dokene.followup.application.FollowUpPolicyRepository;
import io.github.stevdrey.dokene.followup.application.ManualFollowUpResult;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.ManualFollowUpCompletion;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
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
                SELECT cadence_days, explicit_next_date, snoozed_until, last_manual_follow_up_date, version
                FROM dokene.customer_follow_up_policies WHERE tenant_id = ? AND customer_id = ?
                """, (row, index) -> new CustomerFollowUpPolicy(tenantId, customerId,
                        (Integer) row.getObject("cadence_days"), localDate(row.getDate("explicit_next_date")),
                        localDate(row.getDate("snoozed_until")), localDate(row.getDate("last_manual_follow_up_date")),
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
    public ManualFollowUpResult recordManualFollowUp(TenantId tenantId, CustomerId customerId, LocalDate date,
            long expectedVersion, String idempotencyKey, Instant occurredAt, IdentityId actorId,
            TenantMembershipId membershipId) {
        UUID completionId = UUID.randomUUID();
        long nextVersion = nextVersion(expectedVersion);
        var inserted = jdbc.query("""
                INSERT INTO dokene.manual_follow_up_completions
                    (id, tenant_id, customer_id, idempotency_key, completed_on, policy_version,
                     occurred_at, actor_id, membership_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                RETURNING *
                """, this::mapCompletion, completionId, tenantId.value(), customerId.value(), idempotencyKey,
                sqlDate(date), nextVersion, Timestamp.from(occurredAt), actorId.value(), membershipId.value());
        if (inserted.isEmpty()) {
            ManualFollowUpCompletion existing = completion(tenantId, idempotencyKey);
            if (!existing.customerId().equals(customerId)) throw new FollowUpConflictException();
            return new ManualFollowUpResult(existing, false);
        }
        int updated = jdbc.update("""
                UPDATE dokene.customer_follow_up_policies
                SET explicit_next_date = NULL, snoozed_until = NULL, last_manual_follow_up_date = ?, version = ?
                WHERE tenant_id = ? AND customer_id = ? AND version = ?
                """, sqlDate(date), nextVersion, tenantId.value(), customerId.value(), expectedVersion);
        requireUpdated(updated);
        return new ManualFollowUpResult(inserted.getFirst(), true);
    }

    @Override
    public CustomerFollowUpPolicy snooze(TenantId tenantId, CustomerId customerId, LocalDate until,
            long expectedVersion) {
        long nextVersion = nextVersion(expectedVersion);
        int updated = jdbc.update("""
                UPDATE dokene.customer_follow_up_policies SET snoozed_until = ?, version = ?
                WHERE tenant_id = ? AND customer_id = ? AND version = ?
                """, sqlDate(until), nextVersion, tenantId.value(), customerId.value(), expectedVersion);
        requireUpdated(updated);
        return customerPolicy(tenantId, customerId);
    }

    private ManualFollowUpCompletion completion(TenantId tenantId, String key) {
        return jdbc.queryForObject("""
                SELECT * FROM dokene.manual_follow_up_completions WHERE tenant_id = ? AND idempotency_key = ?
                """, this::mapCompletion, tenantId.value(), key);
    }

    private ManualFollowUpCompletion mapCompletion(ResultSet row, int index) throws SQLException {
        return new ManualFollowUpCompletion(row.getObject("id", UUID.class),
                new TenantId(row.getObject("tenant_id", UUID.class)),
                new CustomerId(row.getObject("customer_id", UUID.class)), row.getDate("completed_on").toLocalDate(),
                row.getLong("policy_version"), row.getTimestamp("occurred_at").toInstant(),
                new IdentityId(row.getObject("actor_id", UUID.class)),
                new TenantMembershipId(row.getObject("membership_id", UUID.class)));
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
