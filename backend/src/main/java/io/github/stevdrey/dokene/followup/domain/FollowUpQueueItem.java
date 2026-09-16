package io.github.stevdrey.dokene.followup.domain;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record FollowUpQueueItem(
        CustomerId customerId,
        String displayName,
        String primaryPhone,
        FollowUpStatus status,
        List<FollowUpReason> reasons,
        LocalDate dueDate,
        FollowUpTimingSource timingSource,
        long policyVersion,
        int effectiveCadenceDays,
        Instant lastPurchaseAt,
        LocalDate lastManualFollowUpDate,
        LocalDate lastDismissedDate,
        Instant evaluatedAt
) {
    public FollowUpQueueItem {
        Objects.requireNonNull(customerId, "Customer ID is required");
        Objects.requireNonNull(displayName, "Display name is required");
        Objects.requireNonNull(status, "Status is required");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "Reasons are required"));
        Objects.requireNonNull(dueDate, "Due date is required");
        Objects.requireNonNull(timingSource, "Timing source is required");
        Objects.requireNonNull(evaluatedAt, "Evaluated at is required");
        if (status != FollowUpStatus.DUE && status != FollowUpStatus.OVERDUE) {
            throw new IllegalArgumentException("Queue items must be DUE or OVERDUE");
        }
        if (policyVersion < 0) throw new IllegalArgumentException("Policy version cannot be negative");
        if (effectiveCadenceDays < 1 || effectiveCadenceDays > TenantFollowUpPolicy.MAX_CADENCE_DAYS) {
            throw new IllegalArgumentException("Effective cadence must be between 1 and 3650 days");
        }
    }
}
