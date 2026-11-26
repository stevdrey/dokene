// ABOUTME: The result of one tenant's candidate scan (issue 460, ADR 0036): what it recorded, when, and whether the
// ABOUTME: batch filled so the caller knows more due cycles may remain for a later scan.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpCandidate;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record FollowUpCandidateGeneration(TenantId tenantId, Instant evaluatedAt, Outcome outcome,
        List<FollowUpCandidate> recorded, boolean batchFull) {

    public enum Outcome {
        /** The scan ran; {@code recorded} may be empty when every due cycle already has a candidate. */
        SCANNED,
        /** The tenant is missing or not active, so nothing was scanned. */
        TENANT_INACTIVE
    }

    public FollowUpCandidateGeneration {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(evaluatedAt);
        Objects.requireNonNull(outcome);
        recorded = List.copyOf(recorded);
    }

    static FollowUpCandidateGeneration inactive(TenantId tenantId, Instant evaluatedAt) {
        return new FollowUpCandidateGeneration(tenantId, evaluatedAt, Outcome.TENANT_INACTIVE, List.of(), false);
    }
}
