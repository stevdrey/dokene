// ABOUTME: A recurring follow-up candidate (issue 460, ADR 0036): one customer's due cycle, recorded once per tenant,
// ABOUTME: customer and due date. Carries no contact data; it is a record of eligibility, never an approval or a send.
package io.github.stevdrey.dokene.followup.domain;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The identity of a candidate is {@code (tenantId, customerId, dueDate)}: the due date is the cycle's date in the tenant's
 * calendar, computed from the ADR 0013 anchors without the snooze. Repeated scans of the same cycle therefore name the
 * same candidate, while a dismissal, manual completion, sent message, newer purchase or explicit date starts a new one.
 *
 * @param policyVersion the customer follow-up policy version the generating scan evaluated
 * @param generatedOn the tenant-local date of the generating scan
 */
public record FollowUpCandidate(UUID id, TenantId tenantId, CustomerId customerId, LocalDate dueDate,
        FollowUpTimingSource timingSource, int effectiveCadenceDays, long policyVersion, LocalDate generatedOn,
        Instant generatedAt) {

    public FollowUpCandidate {
        Objects.requireNonNull(id, "Candidate id is required");
        Objects.requireNonNull(tenantId, "Candidate tenant is required");
        Objects.requireNonNull(customerId, "Candidate customer is required");
        Objects.requireNonNull(dueDate, "Candidate due date is required");
        Objects.requireNonNull(timingSource, "Candidate timing source is required");
        Objects.requireNonNull(generatedOn, "Candidate generation date is required");
        Objects.requireNonNull(generatedAt, "Candidate generation instant is required");
        if (timingSource == FollowUpTimingSource.SNOOZE || timingSource == FollowUpTimingSource.NONE) {
            throw new IllegalArgumentException("A candidate needs a cycle anchor, not " + timingSource);
        }
        if (effectiveCadenceDays < 1) {
            throw new IllegalArgumentException("Candidate cadence must be positive");
        }
        if (dueDate.isAfter(generatedOn)) {
            throw new IllegalArgumentException("A candidate cannot be generated before its due date");
        }
    }

    /** {@link FollowUpStatus#DUE} on the generating date, otherwise {@link FollowUpStatus#OVERDUE}. */
    public FollowUpStatus statusWhenGenerated() {
        return dueDate.equals(generatedOn) ? FollowUpStatus.DUE : FollowUpStatus.OVERDUE;
    }
}
