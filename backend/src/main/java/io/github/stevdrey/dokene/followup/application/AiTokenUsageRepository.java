package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.LocalDate;

/**
 * Durable per-tenant AI token usage, one counter per UTC calendar month (ADR 0035). Implementations must be shared
 * by every backend instance and survive restarts, and must run under the active tenant context so that row-level
 * security keeps each tenant's counter isolated.
 */
public interface AiTokenUsageRepository {

    /** Total input plus output tokens recorded for {@code tenantId} in the month starting at {@code periodStart}. */
    long usedTokens(TenantId tenantId, LocalDate periodStart);

    /** Atomically adds the given token counts to the tenant's counter for that month, creating it when absent. */
    void add(TenantId tenantId, LocalDate periodStart, long inputTokens, long outputTokens);
}
