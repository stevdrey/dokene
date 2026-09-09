package io.github.stevdrey.dokene.followup.persistence.jdbc;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpPolicyRepository;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFollowUpPolicyRepository implements FollowUpPolicyRepository {
    private final JdbcTemplate jdbc;

    public JdbcFollowUpPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TenantFollowUpPolicy tenantPolicy(TenantId tenantId) {
        return jdbc.queryForObject("""
                SELECT cadence_days, time_zone FROM dokene.tenant_follow_up_policies WHERE tenant_id = ?
                """, (row, index) -> new TenantFollowUpPolicy(tenantId, row.getInt("cadence_days"),
                        ZoneId.of(row.getString("time_zone"))), tenantId.value());
    }

    @Override
    public CustomerFollowUpPolicy customerPolicy(TenantId tenantId, CustomerId customerId) {
        return jdbc.query("""
                SELECT cadence_days, explicit_next_date, snoozed_until, last_manual_follow_up_date
                FROM dokene.customer_follow_up_policies WHERE tenant_id = ? AND customer_id = ?
                """, (row, index) -> new CustomerFollowUpPolicy(tenantId, customerId,
                        (Integer) row.getObject("cadence_days"), localDate(row.getDate("explicit_next_date")),
                        localDate(row.getDate("snoozed_until")), localDate(row.getDate("last_manual_follow_up_date"))),
                tenantId.value(), customerId.value()).stream().findFirst()
                .orElseGet(() -> CustomerFollowUpPolicy.empty(tenantId, customerId));
    }

    @Override
    public TenantFollowUpPolicy saveTenantPolicy(TenantFollowUpPolicy policy) {
        jdbc.update("""
                UPDATE dokene.tenant_follow_up_policies SET cadence_days = ?, time_zone = ?
                WHERE tenant_id = ?
                """, policy.cadenceDays(), policy.zoneId().getId(), policy.tenantId().value());
        return tenantPolicy(policy.tenantId());
    }

    @Override
    public CustomerFollowUpPolicy saveCustomerPolicy(CustomerFollowUpPolicy policy) {
        jdbc.update("""
                INSERT INTO dokene.customer_follow_up_policies
                    (tenant_id, customer_id, cadence_days, explicit_next_date, snoozed_until,
                     last_manual_follow_up_date)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, customer_id) DO UPDATE SET
                    cadence_days = EXCLUDED.cadence_days,
                    explicit_next_date = EXCLUDED.explicit_next_date,
                    snoozed_until = EXCLUDED.snoozed_until,
                    last_manual_follow_up_date = EXCLUDED.last_manual_follow_up_date
                """, policy.tenantId().value(), policy.customerId().value(), policy.cadenceDays(),
                sqlDate(policy.explicitNextDate()), sqlDate(policy.snoozedUntil()),
                sqlDate(policy.lastManualFollowUpDate()));
        return customerPolicy(policy.tenantId(), policy.customerId());
    }

    @Override
    public CustomerFollowUpPolicy recordManualFollowUp(TenantId tenantId, CustomerId customerId, LocalDate date) {
        CustomerFollowUpPolicy current = customerPolicy(tenantId, customerId);
        return saveCustomerPolicy(new CustomerFollowUpPolicy(tenantId, customerId, current.cadenceDays(),
                null, null, date));
    }

    @Override
    public CustomerFollowUpPolicy snooze(TenantId tenantId, CustomerId customerId, LocalDate until) {
        CustomerFollowUpPolicy current = customerPolicy(tenantId, customerId);
        return saveCustomerPolicy(new CustomerFollowUpPolicy(tenantId, customerId, current.cadenceDays(),
                current.explicitNextDate(), until, current.lastManualFollowUpDate()));
    }

    private LocalDate localDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private Date sqlDate(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }
}
