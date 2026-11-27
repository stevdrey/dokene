// ABOUTME: PostgreSQL follow-up work ledger (issue 458, ADR 0037): bounded enqueue, atomic claims with FOR UPDATE SKIP
// ABOUTME: LOCKED, and fenced lease renewals and outcomes, all naming the tenant explicitly under forced RLS.
package io.github.stevdrey.dokene.followup.persistence.jdbc;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkLedgerRepository;
import io.github.stevdrey.dokene.followup.domain.FollowUpRecoverableWork;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkClaim;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkFailure;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkItem;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkRecoveryAction;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkState;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Claims lock their rows with {@code FOR UPDATE SKIP LOCKED}, so concurrent instances never wait on or claim the same
 * row, and PostgreSQL re-checks the claimable condition on a row that changed before it was locked. Outcomes first lock
 * the current claim's row ({@link #lockClaim}); the caller then judges the lease with a clock read after the lock, and
 * the fenced {@code UPDATE} matches the fencing identity and that unexpired lease, so a stale claimant writes nothing.
 */
@Repository
public class JdbcFollowUpWorkLedgerRepository implements FollowUpWorkLedgerRepository {
    /**
     * The current claim's fencing identity and the metadata it was issued with: tenant, candidate, customer, due date,
     * attempt and token. A claim whose customer or due date was altered matches nothing.
     */
    private static final String CURRENT_CLAIM = """
            tenant_id = ? AND candidate_id = ? AND customer_id = ? AND due_date = ? AND state = 'CLAIMED'
              AND attempts = ? AND claim_token = ?
            """;
    /**
     * A finished, non-collapsed item of the candidate {@code c}'s customer whose latest claim the database recorded
     * after {@code c}: the candidate existed when that follow-up was worked, so it is covered by it.
     */
    private static final String COVERING_ITEM = covering("c");
    private static final Comparator<FollowUpWorkClaim> DUE_ORDER = Comparator.comparing(FollowUpWorkClaim::dueDate)
            .thenComparing(claim -> claim.customerId().value(), JdbcFollowUpCandidateRepository::unsigned);

    private final JdbcTemplate jdbc;

    public JdbcFollowUpWorkLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The open-item check, which matches the partial unique index on open items, skips customers that already have
     * work; that index makes a concurrent enqueue of the same customer skip it through ON CONFLICT instead of adding a
     * second open item. The covering check skips candidates that a finished claim of the same customer already covers
     * but that no collapse has recorded yet, so they never reopen as another follow-up.
     */
    @Override
    public int enqueue(TenantId tenantId, Instant now, int limit) {
        requirePositive(limit);
        Timestamp at = Timestamp.from(now);
        return jdbc.update("""
                INSERT INTO dokene.follow_up_work_items
                    (candidate_id, tenant_id, customer_id, due_date, state, attempts, available_at, enqueued_at,
                     updated_at)
                SELECT latest.id, latest.tenant_id, latest.customer_id, latest.due_date, 'PENDING', 0, ?, ?, ?
                FROM (
                    SELECT DISTINCT ON (c.customer_id) c.id, c.tenant_id, c.customer_id, c.due_date
                    FROM dokene.follow_up_candidates c
                    WHERE c.tenant_id = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM dokene.follow_up_work_items item
                          WHERE item.tenant_id = c.tenant_id AND item.candidate_id = c.id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM dokene.follow_up_work_items covering
                          WHERE
                          """ + COVERING_ITEM + """
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM dokene.follow_up_work_items open_item
                          WHERE open_item.tenant_id = c.tenant_id AND open_item.customer_id = c.customer_id
                            AND (open_item.state IN ('PENDING', 'CLAIMED', 'RETRY_DUE')
                                 OR (open_item.state = 'EXHAUSTED'
                                     AND open_item.last_failure IN ('OUTCOME_UNKNOWN', 'LEASE_EXPIRED')))
                      )
                    ORDER BY c.customer_id, c.due_date DESC
                ) latest
                WHERE EXISTS (SELECT 1 FROM dokene.tenants t WHERE t.id = ? AND t.status = 'ACTIVE')
                ORDER BY latest.due_date ASC, latest.customer_id ASC
                LIMIT ?
                ON CONFLICT DO NOTHING
                """, at, at, at, tenantId.value(), tenantId.value(), limit);
    }

    /**
     * Records the oldest {@code limit} covered candidates without an item as completed together with the latest
     * finished claim that covers them.
     */
    @Override
    public int collapseCovered(TenantId tenantId, Instant now, int limit) {
        requirePositive(limit);
        Timestamp at = Timestamp.from(now);
        return jdbc.update("""
                INSERT INTO dokene.follow_up_work_items
                    (candidate_id, tenant_id, customer_id, due_date, state, attempts, available_at, collapsed_into,
                     enqueued_at, finished_at, updated_at)
                SELECT c.id, c.tenant_id, c.customer_id, c.due_date, 'COMPLETED', 0, ?, cover.candidate_id, ?, ?, ?
                FROM dokene.follow_up_candidates c
                CROSS JOIN LATERAL (
                    SELECT covering.candidate_id FROM dokene.follow_up_work_items covering
                    WHERE
                    """ + COVERING_ITEM + """
                    ORDER BY covering.claim_recorded_at DESC, covering.candidate_id ASC
                    LIMIT 1
                ) cover
                WHERE c.tenant_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM dokene.follow_up_work_items item
                      WHERE item.tenant_id = c.tenant_id AND item.candidate_id = c.id
                  )
                ORDER BY c.due_date ASC, c.id ASC
                LIMIT ?
                ON CONFLICT DO NOTHING
                """, at, at, at, at, tenantId.value(), limit);
    }

    /**
     * An enqueue whose statement snapshot predates a newer covering item can still insert a covered candidate as
     * {@code PENDING} once that item has finished and released its customer. Any statement that sees such an item was
     * itself started after the covering item finished, so this fresh read finds the cover and resolves the item, which
     * releases the customer's slot again.
     */
    @Override
    public int reconcileCovered(TenantId tenantId, Instant now, int limit) {
        requirePositive(limit);
        Timestamp at = Timestamp.from(now);
        return jdbc.update("""
                WITH covered AS (
                    SELECT w.candidate_id, cover.candidate_id AS covering_id
                    FROM dokene.follow_up_work_items w
                    JOIN dokene.follow_up_candidates cand
                      ON cand.tenant_id = w.tenant_id AND cand.id = w.candidate_id
                    CROSS JOIN LATERAL (
                        SELECT covering.candidate_id FROM dokene.follow_up_work_items covering
                        WHERE
                        """ + covering("cand") + """
                        ORDER BY covering.claim_recorded_at DESC, covering.candidate_id ASC
                        LIMIT 1
                    ) cover
                    WHERE w.tenant_id = ? AND w.state = 'PENDING' AND w.attempts = 0
                    ORDER BY w.due_date ASC, w.customer_id ASC
                    LIMIT ?
                    FOR UPDATE OF w SKIP LOCKED
                )
                UPDATE dokene.follow_up_work_items w
                SET state = 'COMPLETED', collapsed_into = covered.covering_id, finished_at = ?, updated_at = ?
                FROM covered
                WHERE w.tenant_id = ? AND w.candidate_id = covered.candidate_id
                """, tenantId.value(), limit, at, at, tenantId.value());
    }

    @Override
    public int exhaustSpent(TenantId tenantId, Instant now, int maxAttempts, int limit) {
        requirePositive(limit);
        Timestamp at = Timestamp.from(now);
        return jdbc.update("""
                WITH spent AS (
                    SELECT s.candidate_id FROM dokene.follow_up_work_items s
                    WHERE s.tenant_id = ? AND s.attempts - s.attempt_base >= ?
                      AND ((s.state = 'CLAIMED' AND s.lease_expires_at <= ?) OR s.state = 'RETRY_DUE')
                    ORDER BY s.due_date ASC, s.customer_id ASC
                    LIMIT ?
                    FOR UPDATE OF s SKIP LOCKED
                )
                UPDATE dokene.follow_up_work_items w
                SET state = 'EXHAUSTED',
                    last_failure = CASE WHEN w.state = 'CLAIMED' THEN 'LEASE_EXPIRED' ELSE w.last_failure END,
                    finished_at = ?, updated_at = ?
                FROM spent
                WHERE w.tenant_id = ? AND w.candidate_id = spent.candidate_id
                """, tenantId.value(), maxAttempts, at, limit, at, at, tenantId.value());
    }

    @Override
    public List<FollowUpWorkClaim> claim(TenantId tenantId, FollowUpWorkerId worker, Instant now,
            Instant leaseExpiresAt, int maxAttempts, int limit) {
        requirePositive(limit);
        if (!leaseExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("A lease must expire after its claim");
        }
        Timestamp at = Timestamp.from(now);
        List<FollowUpWorkClaim> claims = jdbc.query("""
                WITH claimable AS (
                    SELECT c.candidate_id FROM dokene.follow_up_work_items c
                    WHERE c.tenant_id = ? AND c.attempts - c.attempt_base < ?
                      AND ((c.state IN ('PENDING', 'RETRY_DUE') AND c.available_at <= ?)
                           OR (c.state = 'CLAIMED' AND c.lease_expires_at <= ?))
                      AND EXISTS (SELECT 1 FROM dokene.tenants t WHERE t.id = ? AND t.status = 'ACTIVE')
                      AND NOT (c.state = 'PENDING' AND c.attempts = 0 AND EXISTS (
                          SELECT 1 FROM dokene.follow_up_candidates cand
                          JOIN dokene.follow_up_work_items covering ON
                          """ + covering("cand") + """
                          WHERE cand.tenant_id = c.tenant_id AND cand.id = c.candidate_id
                      ))
                    ORDER BY c.due_date ASC, c.customer_id ASC
                    LIMIT ?
                    FOR UPDATE OF c SKIP LOCKED
                )
                UPDATE dokene.follow_up_work_items w
                SET state = 'CLAIMED', attempts = w.attempts + 1, claim_token = gen_random_uuid(), claimed_by = ?,
                    claimed_at = ?, claim_recorded_at = statement_timestamp(), lease_expires_at = ?, updated_at = ?
                FROM claimable
                WHERE w.tenant_id = ? AND w.candidate_id = claimable.candidate_id
                RETURNING w.candidate_id, w.tenant_id, w.customer_id, w.due_date, w.attempts, w.claim_token,
                    w.claimed_by, w.claimed_at, w.lease_expires_at
                """, this::mapClaim, tenantId.value(), maxAttempts, at, at, tenantId.value(), limit, worker.value(),
                at, Timestamp.from(leaseExpiresAt), at, tenantId.value());
        return claims.stream().sorted(DUE_ORDER).toList();
    }

    @Override
    public Optional<Instant> lockClaim(FollowUpWorkClaim claim) {
        return jdbc.query("""
                SELECT lease_expires_at FROM dokene.follow_up_work_items
                WHERE
                """ + CURRENT_CLAIM + """
                FOR UPDATE
                """, (row, index) -> instant(row, "lease_expires_at"), fence(claim)).stream().findFirst();
    }

    @Override
    public Optional<Instant> renew(FollowUpWorkClaim claim, Instant now, Instant leaseExpiresAt) {
        Timestamp at = Timestamp.from(now);
        return jdbc.query("""
                UPDATE dokene.follow_up_work_items
                SET lease_expires_at = GREATEST(lease_expires_at, ?), updated_at = ?
                WHERE
                """ + CURRENT_CLAIM + """
                  AND lease_expires_at > ?
                RETURNING lease_expires_at
                """, (row, index) -> instant(row, "lease_expires_at"),
                parameters(claim, at, Timestamp.from(leaseExpiresAt), at)).stream().findFirst();
    }

    @Override
    public boolean complete(FollowUpWorkClaim claim, Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.update("""
                UPDATE dokene.follow_up_work_items
                SET state = 'COMPLETED', finished_at = ?, updated_at = ?
                WHERE
                """ + CURRENT_CLAIM + """
                  AND lease_expires_at > ?
                """, parameters(claim, at, at, at)) == 1;
    }

    /**
     * The customer and the claim instant come from the completed item itself, never from {@code claim}, so a claim
     * object with altered metadata cannot collapse another customer's candidates or candidates it never covered.
     */
    @Override
    public int collapseInto(FollowUpWorkClaim claim, Instant now, int limit) {
        requirePositive(limit);
        Timestamp at = Timestamp.from(now);
        return jdbc.update("""
                INSERT INTO dokene.follow_up_work_items
                    (candidate_id, tenant_id, customer_id, due_date, state, attempts, available_at, collapsed_into,
                     enqueued_at, finished_at, updated_at)
                SELECT c.id, c.tenant_id, c.customer_id, c.due_date, 'COMPLETED', 0, ?, completed.candidate_id, ?, ?,
                       ?
                FROM dokene.follow_up_work_items completed
                JOIN dokene.follow_up_candidates c
                  ON c.tenant_id = completed.tenant_id AND c.customer_id = completed.customer_id
                WHERE completed.tenant_id = ? AND completed.candidate_id = ? AND completed.state = 'COMPLETED'
                  AND completed.collapsed_into IS NULL
                  AND c.id <> completed.candidate_id AND c.recorded_at <= completed.claim_recorded_at
                  AND NOT EXISTS (
                      SELECT 1 FROM dokene.follow_up_work_items item
                      WHERE item.tenant_id = c.tenant_id AND item.candidate_id = c.id
                  )
                ORDER BY c.due_date ASC, c.id ASC
                LIMIT ?
                ON CONFLICT DO NOTHING
                """, at, at, at, at, claim.tenantId().value(), claim.candidateId(), limit);
    }

    @Override
    public Optional<FollowUpWorkState> fail(FollowUpWorkClaim claim, FollowUpWorkFailure failure, Instant now,
            Instant retryAt, int maxAttempts) {
        Timestamp at = Timestamp.from(now);
        return jdbc.query("""
                UPDATE dokene.follow_up_work_items
                SET state = CASE WHEN attempts - attempt_base >= ? THEN 'EXHAUSTED' ELSE 'RETRY_DUE' END,
                    available_at = CASE WHEN attempts - attempt_base >= ? THEN available_at ELSE ? END,
                    finished_at = CASE WHEN attempts - attempt_base >= ? THEN ?::timestamptz ELSE NULL END,
                    last_failure = ?, updated_at = ?
                WHERE
                """ + CURRENT_CLAIM + """
                  AND lease_expires_at > ?
                RETURNING state
                """, (row, index) -> FollowUpWorkState.valueOf(row.getString("state")),
                parameters(claim, at, maxAttempts, maxAttempts, Timestamp.from(retryAt), maxAttempts, at,
                        failure.name(), at)).stream().findFirst();
    }

    @Override
    public boolean exhaust(FollowUpWorkClaim claim, FollowUpWorkFailure failure, Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.update("""
                UPDATE dokene.follow_up_work_items
                SET state = 'EXHAUSTED', last_failure = ?, finished_at = ?, updated_at = ?
                WHERE
                """ + CURRENT_CLAIM + """
                  AND lease_expires_at > ?
                """, parameters(claim, at, failure.name(), at, at)) == 1;
    }

    @Override
    public List<FollowUpWorkItem> findItems(TenantId tenantId, int limit) {
        requirePositive(limit);
        return jdbc.query("""
                SELECT * FROM dokene.follow_up_work_items
                WHERE tenant_id = ?
                ORDER BY due_date ASC, customer_id ASC, candidate_id ASC
                LIMIT ?
                """, this::mapItem, tenantId.value(), limit);
    }

    @Override
    public List<FollowUpRecoverableWork> findRecoverable(TenantId tenantId, int limit) {
        requirePositive(limit);
        return jdbc.query("""
                SELECT * FROM dokene.follow_up_work_items
                WHERE tenant_id = ? AND state = 'EXHAUSTED' AND collapsed_into IS NULL
                ORDER BY finished_at ASC, candidate_id ASC
                LIMIT ?
                """, this::mapRecoverable, tenantId.value(), limit);
    }

    @Override
    public Optional<FollowUpRecoverableWork> lockRecoverable(TenantId tenantId, UUID candidateId) {
        return jdbc.query("""
                SELECT * FROM dokene.follow_up_work_items
                WHERE tenant_id = ? AND candidate_id = ? AND collapsed_into IS NULL AND last_failure IS NOT NULL
                FOR UPDATE
                """, this::mapRecoverable, tenantId.value(), candidateId).stream().findFirst();
    }

    /**
     * The customer's other open items, and its other non-collapsed items whose candidate the database recorded after
     * this one (a newer cycle, finished or not), block the requeue: a superseded cycle is never worked again. Both
     * compare database state only. The open-work part matches the partial unique index on open items; a concurrent
     * enqueue that wins the slot anyway makes this update fail on that index instead of opening a second item.
     */
    @Override
    public boolean requeue(TenantId tenantId, UUID candidateId, int expectedRecoveryCount, Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.update("""
                UPDATE dokene.follow_up_work_items w
                SET state = 'RETRY_DUE', available_at = ?, attempt_base = w.attempts, finished_at = NULL,
                    recovery_count = w.recovery_count + 1, last_recovery_action = 'REQUEUE', last_recovered_at = ?,
                    updated_at = ?
                WHERE w.tenant_id = ? AND w.candidate_id = ? AND w.state = 'EXHAUSTED' AND w.collapsed_into IS NULL
                  AND w.recovery_count = ?
                  AND w.last_failure IN ('PROCESSING_ERROR', 'DEPENDENCY_UNAVAILABLE', 'REJECTED')
                  AND NOT EXISTS (
                      SELECT 1 FROM dokene.follow_up_work_items other
                      JOIN dokene.follow_up_candidates other_candidate
                        ON other_candidate.tenant_id = other.tenant_id AND other_candidate.id = other.candidate_id
                      JOIN dokene.follow_up_candidates own
                        ON own.tenant_id = w.tenant_id AND own.id = w.candidate_id
                      WHERE other.tenant_id = w.tenant_id AND other.customer_id = w.customer_id
                        AND other.candidate_id <> w.candidate_id
                        AND (other.state IN ('PENDING', 'CLAIMED', 'RETRY_DUE')
                             OR (other.state = 'EXHAUSTED'
                                 AND other.last_failure IN ('OUTCOME_UNKNOWN', 'LEASE_EXPIRED'))
                             OR (other.collapsed_into IS NULL AND other_candidate.recorded_at > own.recorded_at))
                  )
                """, at, at, at, tenantId.value(), candidateId, expectedRecoveryCount) == 1;
    }

    @Override
    public boolean resolve(TenantId tenantId, UUID candidateId, int expectedRecoveryCount, Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.update("""
                UPDATE dokene.follow_up_work_items
                SET state = 'COMPLETED', finished_at = ?, recovery_count = recovery_count + 1,
                    last_recovery_action = 'RESOLVE', last_recovered_at = ?, updated_at = ?
                WHERE tenant_id = ? AND candidate_id = ? AND state = 'EXHAUSTED' AND collapsed_into IS NULL
                  AND recovery_count = ?
                """, at, at, at, tenantId.value(), candidateId, expectedRecoveryCount) == 1;
    }

    /**
     * A finished, non-collapsed item of the candidate {@code alias}'s customer whose latest claim the database recorded
     * after that candidate: the candidate existed when that follow-up was worked, so it is covered by it.
     */
    private static String covering(String alias) {
        return """
                covering.tenant_id = %1$s.tenant_id AND covering.customer_id = %1$s.customer_id
                  AND covering.state IN ('COMPLETED', 'EXHAUSTED') AND covering.collapsed_into IS NULL
                  AND covering.claim_recorded_at >= %1$s.recorded_at
                """.formatted(alias);
    }

    private static Object[] fence(FollowUpWorkClaim claim) {
        return new Object[] {claim.tenantId().value(), claim.candidateId(), claim.customerId().value(),
                Date.valueOf(claim.dueDate()), claim.attempt(), claim.claimToken()};
    }

    /** The {@code SET} values, then the claim's fence, then the instant its lease must outlast. */
    private static Object[] parameters(FollowUpWorkClaim claim, Timestamp leaseOutlasts, Object... values) {
        Object[] fence = fence(claim);
        Object[] all = new Object[values.length + fence.length + 1];
        System.arraycopy(values, 0, all, 0, values.length);
        System.arraycopy(fence, 0, all, values.length, fence.length);
        all[all.length - 1] = leaseOutlasts;
        return all;
    }

    private static void requirePositive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Work limit must be positive");
        }
    }

    private FollowUpWorkClaim mapClaim(ResultSet row, int index) throws SQLException {
        return new FollowUpWorkClaim(row.getObject("candidate_id", UUID.class),
                new TenantId(row.getObject("tenant_id", UUID.class)),
                new CustomerId(row.getObject("customer_id", UUID.class)), row.getDate("due_date").toLocalDate(),
                new FollowUpWorkerId(row.getString("claimed_by")), row.getInt("attempts"),
                row.getObject("claim_token", UUID.class), instant(row, "claimed_at"),
                instant(row, "lease_expires_at"));
    }

    private FollowUpWorkItem mapItem(ResultSet row, int index) throws SQLException {
        return new FollowUpWorkItem(row.getObject("candidate_id", UUID.class),
                new TenantId(row.getObject("tenant_id", UUID.class)),
                new CustomerId(row.getObject("customer_id", UUID.class)), row.getDate("due_date").toLocalDate(),
                FollowUpWorkState.valueOf(row.getString("state")), row.getInt("attempts"),
                instant(row, "available_at"),
                Optional.ofNullable(row.getString("claimed_by")).map(FollowUpWorkerId::new),
                Optional.ofNullable(row.getTimestamp("lease_expires_at")).map(Timestamp::toInstant),
                Optional.ofNullable(row.getString("last_failure")).map(FollowUpWorkFailure::valueOf),
                Optional.ofNullable(row.getObject("collapsed_into", UUID.class)), instant(row, "enqueued_at"),
                Optional.ofNullable(row.getTimestamp("finished_at")).map(Timestamp::toInstant));
    }

    private FollowUpRecoverableWork mapRecoverable(ResultSet row, int index) throws SQLException {
        return new FollowUpRecoverableWork(row.getObject("candidate_id", UUID.class),
                new CustomerId(row.getObject("customer_id", UUID.class)), row.getDate("due_date").toLocalDate(),
                FollowUpWorkState.valueOf(row.getString("state")),
                FollowUpWorkFailure.valueOf(row.getString("last_failure")), row.getInt("attempts"),
                Optional.ofNullable(row.getTimestamp("finished_at")).map(Timestamp::toInstant),
                row.getInt("recovery_count"),
                Optional.ofNullable(row.getString("last_recovery_action")).map(FollowUpWorkRecoveryAction::valueOf));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }
}
