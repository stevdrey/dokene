// ABOUTME: Persistence port for recurring follow-up candidates (issue 460, ADR 0036): an idempotent, bounded insert of
// ABOUTME: the due cycles the due queue derivation reports, and a tenant-scoped read in due order.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpCandidate;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.List;

public interface FollowUpCandidateRepository {

    /**
     * Records a candidate for at most {@code limit} due or overdue customers whose current cycle has none yet, in
     * {@code dueDate, customerId} order, using the same eligibility, cadence and tenant time zone as the due queue.
     * Runs in the caller's transaction under a trusted tenant scope. A cycle another run recorded first is skipped, so
     * concurrent runs together record each cycle once.
     *
     * @return the candidates this call recorded, in due order
     */
    List<FollowUpCandidate> recordDueCandidates(TenantId tenantId, Instant evaluatedAt, int limit);

    /** At most {@code limit} of the tenant's candidates in {@code dueDate, customerId} order. */
    List<FollowUpCandidate> findCandidates(TenantId tenantId, int limit);
}
