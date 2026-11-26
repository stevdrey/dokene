// ABOUTME: PostgreSQL candidate persistence (issue 460, ADR 0036): one INSERT ... SELECT over the shared due queue
// ABOUTME: derivation, deduplicated by the (tenant, customer, due date) unique key, under forced RLS.
package io.github.stevdrey.dokene.followup.persistence.jdbc;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateRepository;
import io.github.stevdrey.dokene.followup.domain.FollowUpCandidate;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFollowUpCandidateRepository implements FollowUpCandidateRepository {
    /**
     * The SQL order {@code due_date, customer_id}. PostgreSQL orders UUIDs by unsigned bytes, while
     * {@link UUID#compareTo} compares signed halves, so the halves are compared unsigned here.
     */
    static final Comparator<FollowUpCandidate> DUE_ORDER = Comparator.comparing(FollowUpCandidate::dueDate)
            .thenComparing(candidate -> candidate.customerId().value(), JdbcFollowUpCandidateRepository::unsigned);

    private final JdbcTemplate jdbc;

    public JdbcFollowUpCandidateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The NOT EXISTS skips cycles already recorded before the LIMIT applies, so a full batch never hides newer cycles
     * behind older ones. ON CONFLICT covers a concurrent run that inserts the same cycle after this statement's snapshot:
     * PostgreSQL makes this insert wait for that one and then skip it.
     */
    @Override
    public List<FollowUpCandidate> recordDueCandidates(TenantId tenantId, Instant evaluatedAt, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Candidate limit must be positive");
        }
        String sql = FollowUpDueSql.EVALUATED_CANDIDATES + """
                INSERT INTO dokene.follow_up_candidates
                    (id, tenant_id, customer_id, due_date, timing_source, effective_cadence_days, policy_version,
                     generated_on, generated_at)
                SELECT gen_random_uuid(), ?, ec.customer_id, ec.due_date, ec.timing_source, ec.effective_cadence,
                       ec.policy_version, ec.tenant_today, ?
                FROM evaluated_candidates ec
                WHERE """ + FollowUpDueSql.DUE_PREDICATE + """
                  AND NOT EXISTS (
                      SELECT 1 FROM dokene.follow_up_candidates existing
                      WHERE existing.tenant_id = ? AND existing.customer_id = ec.customer_id
                        AND existing.due_date = ec.due_date
                  )
                ORDER BY ec.due_date ASC, ec.customer_id ASC
                LIMIT ?
                ON CONFLICT (tenant_id, customer_id, due_date) DO NOTHING
                RETURNING *
                """;
        Timestamp generatedAt = Timestamp.from(evaluatedAt);
        List<FollowUpCandidate> recorded = jdbc.query(sql, this::map, tenantId.value(),
                evaluatedAt.atOffset(ZoneOffset.UTC), tenantId.value(), tenantId.value(), generatedAt,
                tenantId.value(), limit);
        return recorded.stream().sorted(DUE_ORDER).toList();
    }

    @Override
    public List<FollowUpCandidate> findCandidates(TenantId tenantId, int limit) {
        return jdbc.query("""
                SELECT * FROM dokene.follow_up_candidates
                WHERE tenant_id = ?
                ORDER BY due_date ASC, customer_id ASC
                LIMIT ?
                """, this::map, tenantId.value(), limit);
    }

    static int unsigned(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(),
                right.getLeastSignificantBits());
    }

    private FollowUpCandidate map(ResultSet row, int index) throws SQLException {
        return new FollowUpCandidate(row.getObject("id", UUID.class),
                new TenantId(row.getObject("tenant_id", UUID.class)),
                new CustomerId(row.getObject("customer_id", UUID.class)), row.getDate("due_date").toLocalDate(),
                FollowUpTimingSource.valueOf(row.getString("timing_source")), row.getInt("effective_cadence_days"),
                row.getLong("policy_version"), row.getDate("generated_on").toLocalDate(),
                row.getTimestamp("generated_at").toInstant());
    }
}
