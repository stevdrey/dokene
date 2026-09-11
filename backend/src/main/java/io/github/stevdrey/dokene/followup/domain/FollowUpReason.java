package io.github.stevdrey.dokene.followup.domain;

public enum FollowUpReason {
    DO_NOT_CONTACT,
    CUSTOMER_ARCHIVED,
    NO_ELIGIBLE_CONTACT,
    NO_PURCHASE_HISTORY,
    SNOOZED,
    EXPLICIT_DATE_NOT_DUE,
    CADENCE_NOT_DUE,
    DUE_TODAY,
    OVERDUE
}
