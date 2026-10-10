// ABOUTME: Deterministic result of one follow up evaluation: status, reasons, timing and the cadence anchor.
// ABOUTME: anchorDate is the latest action or purchase date in the tenant zone; null without any history.
package io.github.stevdrey.dokene.followup.domain;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

public record FollowUpEvaluation(CustomerId customerId, FollowUpStatus status, List<FollowUpReason> reasons,
        Instant evaluatedAt, LocalDate tenantDate, ZoneId tenantZone, LocalDate nextFollowUpDate,
        FollowUpTimingSource timingSource, int effectiveCadenceDays, Instant lastPurchaseAt, LocalDate anchorDate) {
    public FollowUpEvaluation {
        Objects.requireNonNull(customerId, "Customer ID is required");
        Objects.requireNonNull(status, "Status is required");
        reasons = List.copyOf(reasons);
        if (reasons.isEmpty()) throw new IllegalArgumentException("At least one reason is required");
        Objects.requireNonNull(evaluatedAt, "Evaluation timestamp is required");
        Objects.requireNonNull(tenantDate, "Tenant date is required");
        Objects.requireNonNull(tenantZone, "Tenant time zone is required");
        Objects.requireNonNull(timingSource, "Timing source is required");
    }

    /** Callers that predate the anchor date (spec 0003) keep compiling; the anchor is simply unknown. */
    public FollowUpEvaluation(CustomerId customerId, FollowUpStatus status, List<FollowUpReason> reasons,
            Instant evaluatedAt, LocalDate tenantDate, ZoneId tenantZone, LocalDate nextFollowUpDate,
            FollowUpTimingSource timingSource, int effectiveCadenceDays, Instant lastPurchaseAt) {
        this(customerId, status, reasons, evaluatedAt, tenantDate, tenantZone, nextFollowUpDate, timingSource,
                effectiveCadenceDays, lastPurchaseAt, null);
    }

    public boolean eligible() {
        return status == FollowUpStatus.DUE || status == FollowUpStatus.OVERDUE;
    }
}
