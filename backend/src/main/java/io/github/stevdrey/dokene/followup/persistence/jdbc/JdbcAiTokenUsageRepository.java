package io.github.stevdrey.dokene.followup.persistence.jdbc;

import io.github.stevdrey.dokene.followup.application.AiTokenUsageRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL store of the monthly AI token counters (V42, ADR 0035). Row-level security limits every statement to
 * the verified tenant of the connection; the explicit tenant predicate is defense in depth, not the boundary.
 */
@Repository
public class JdbcAiTokenUsageRepository implements AiTokenUsageRepository {
    private final JdbcTemplate jdbc;

    public JdbcAiTokenUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC template is required");
    }

    @Override
    public long usedTokens(TenantId tenantId, LocalDate periodStart) {
        Long used = jdbc.query("""
                SELECT input_tokens + output_tokens AS used_tokens FROM dokene.ai_token_usage
                WHERE tenant_id = ? AND period_start = ?
                """, rows -> rows.next() ? rows.getLong("used_tokens") : 0L,
                tenantId.value(), Date.valueOf(periodStart));
        return used == null ? 0L : used;
    }

    @Override
    public void add(TenantId tenantId, LocalDate periodStart, long inputTokens, long outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("Token counts cannot be negative");
        }
        // One statement: concurrent requests of the same tenant on any instance add up without a lost update.
        jdbc.update("""
                INSERT INTO dokene.ai_token_usage (tenant_id, period_start, input_tokens, output_tokens, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (tenant_id, period_start) DO UPDATE SET
                    input_tokens = dokene.ai_token_usage.input_tokens + EXCLUDED.input_tokens,
                    output_tokens = dokene.ai_token_usage.output_tokens + EXCLUDED.output_tokens,
                    updated_at = EXCLUDED.updated_at
                """, tenantId.value(), Date.valueOf(periodStart), inputTokens, outputTokens);
    }
}
